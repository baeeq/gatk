package org.broadinstitute.hellbender.tools.spark.pathseq;

import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.broadinstitute.barclay.argparser.Argument;
import org.broadinstitute.barclay.argparser.ArgumentCollection;
import org.broadinstitute.barclay.argparser.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.programgroups.SparkProgramGroup;
import org.broadinstitute.hellbender.engine.spark.GATKSparkTool;
import org.broadinstitute.hellbender.engine.spark.datasources.ReadsSparkSink;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.ReadsWriteFormat;
import scala.Tuple2;

import java.io.IOException;

/**
 * This Spark tool is the first step in the PathSeq pipeline.
 * Input: set of unaligned or host-aligned reads. Optional: host K-mer file and bwa mem image.
 * Output: set of high quality non-host reads.
 *
 * Filtering steps:
 * 1) Remove secondary and supplementary reads
 * 2) Mask repetitive sequences with 'N' and base quality --dustPhred using symmetric DUST
 * 3) Hard clip read ends using base qualities
 * 4) Remove reads shorter than --minClippedReadLength
 * 5) Mask bases whose Phred score is less than --minBaseQuality with 'N'
 * 6) Remove reads whose fraction of bases that are 'N' is greater than --maxAmbiguousBaseFraction
 * 7) If specified, Remove reads containing one or more kmers --kmerLibraryPath
 * 8) If specified, remove reads that align to the host BWA image --bwamemIndexImage with at least --minCoverage and --minIdentity
 * 9) If --filterDuplicates is set, remove exact duplicate reads (not using Mark Duplicates because it requires aligned reads)
 *
 * Notes:
 *
 * - Steps 2 - 6 can be skipped by setting --skipFilters.
 * - The tool assumes the BAM file is unaligned by default. If the BAM is aligned to the host, use --isHostAligned to filter.
 * - Output will be two BAM files, outputPath.paired.bam and outputPath.unpaired.bam containing paired and unpaired reads.
 * - If the resulting set of reads is empty, the file will not be written.
 * - If --unpaired is set, pairedness flags will not be corrected after filtering, and all reads will be written to
 *     outputPath.unpaired.bam. This improves performance by avoiding shuffles but violates the SAM format specification.
 *
 */
@CommandLineProgramProperties(summary = "PathSeq read preprocessing and host organism filtering",
        oneLineSummary = "PathSeqFilter on Spark",
        programGroup = SparkProgramGroup.class)
public final class PathSeqFilterSpark extends GATKSparkTool {

    private static final long serialVersionUID = 1L;

    @Argument(doc = "Base uri for the output file(s). Paired and unpaired reads will be written to uri appended with" +
            " '.paired.bam' and '.unpaired.bam'",
            shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME)
    public String outputPath;

    @ArgumentCollection
    public PSFilterArgumentCollection filterArgs = new PSFilterArgumentCollection();

    @Override
    public boolean requiresReads() {
        return true;
    }

    @Override
    protected void runTool(final JavaSparkContext ctx) {

        final SAMFileHeader header = getHeaderForReads();
        if (filterArgs.alignedInput && (header.getSequenceDictionary() == null || header.getSequenceDictionary().isEmpty())) {
            logger.warn("--isHostAligned is true but the BAM header contains no sequences");
        }
        if (!filterArgs.alignedInput && header.getSequenceDictionary() != null && !header.getSequenceDictionary().isEmpty()) {
            logger.warn("--isHostAligned is false but there are one or more sequences in the BAM header");
        }
        header.setSequenceDictionary(new SAMSequenceDictionary());

        try (final PSFilter filter = new PSFilter(ctx, filterArgs, getReads(), header)) {

            final Tuple2<JavaRDD<GATKRead>, JavaRDD<GATKRead>> result = filter.doFilter();
            final JavaRDD<GATKRead> pairedReads = result._1;
            final JavaRDD<GATKRead> unpairedReads = result._2;

            if (!pairedReads.isEmpty()) {
                final SAMFileHeader.SortOrder oldSortOrder = header.getSortOrder();
                header.setSortOrder(SAMFileHeader.SortOrder.queryname);
                writeReads(ctx, outputPath + ".paired.bam", pairedReads, header);
                header.setSortOrder(oldSortOrder);
            } else {
                logger.info("No paired reads to write - BAM will not be written.");
            }
            if (!unpairedReads.isEmpty()) {
                writeReads(ctx, outputPath + ".unpaired.bam", unpairedReads, header);
            } else {
                logger.info("No unpaired reads to write - BAM will not be written.");
            }
        }
    }

    private void writeReads(final JavaSparkContext ctx, final String outputFile, JavaRDD<GATKRead> reads,
                            final SAMFileHeader header) {
        try {
            ReadsSparkSink.writeReads(ctx, outputFile,
                    hasReference() ? referenceArguments.getReferenceFile().getAbsolutePath() : null,
                    reads, header, shardedOutput ? ReadsWriteFormat.SHARDED : ReadsWriteFormat.SINGLE,
                    getRecommendedNumReducers());
        } catch (IOException e) {
            throw new UserException.CouldNotCreateOutputFile(outputFile,"writing failed", e);
        }
    }


}
