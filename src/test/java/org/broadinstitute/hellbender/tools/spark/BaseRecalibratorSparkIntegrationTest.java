package org.broadinstitute.hellbender.tools.spark;

import htsjdk.samtools.ValidationStringency;
import org.broadinstitute.hellbender.CommandLineProgramTest;
import org.broadinstitute.hellbender.engine.datasources.ReferenceAPISource;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.walkers.bqsr.BQSRTestData;
import org.broadinstitute.hellbender.utils.test.IntegrationTestSpec;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.test.SamAssertionUtils;
import org.broadinstitute.hellbender.utils.test.ArgumentsBuilder;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

public class BaseRecalibratorSparkIntegrationTest extends CommandLineProgramTest {

    private final static String THIS_TEST_FOLDER = "org/broadinstitute/hellbender/tools/BQSR/";

    private static final class BQSRTest {
        final String referenceURL;
        final String bam;
        final String knownSites;
        final String args;
        final String expectedFileName;

        private BQSRTest(String referenceURL, String bam, String knownSites, String args, String expectedFileName) {
            this.referenceURL = referenceURL;
            this.bam = bam;
            this.knownSites = knownSites;
            this.args = args;
            this.expectedFileName = expectedFileName;
        }

        public String getCommandLineNoApiKey() {
            return  " -R " + referenceURL +
                    " -I " + bam +
                    " " + args +
                    (knownSites.isEmpty() ? "": " -knownSites " + knownSites) +
                    " -O %s" +
                    " -sortAllCols";
        }

        public String getCommandLine() {
            return  getCommandLineNoApiKey() +
                    " --apiKey " + getGCPTestApiKey();
        }

        @Override
        public String toString() {
            return String.format("BQSR(bam='%s', args='%s')", bam, args);
        }
    }

    private String getResourceDir(){
        return getTestDataDir() + "/" + "BQSR" + "/";
    }

    private String getCloudInputs() {
        return getGCPTestInputPath() + THIS_TEST_FOLDER;
    }

    @DataProvider(name = "BQSRTest")
    public Object[][] createBQSRTestData() {
        final String localResources =  getResourceDir();
        final String hg19Ref = ReferenceAPISource.HG19_REF_ID;
        final String GRCh37Ref = ReferenceAPISource.URL_PREFIX + ReferenceAPISource.GRCH37_REF_ID;
        final String HiSeqBam_chr20 = localResources + WGS_B37_CH20_1M_1M1K_BAM;
        final String dbSNPb37_chr20 = localResources + DBSNP_138_B37_CH20_1M_1M1K_VCF;
        final String moreSites = getResourceDir() + "bqsr.fakeSitesForTesting.b37.chr17.vcf"; //for testing 2 input files (FIXME - this file is bogus because uses chr17)

        return new Object[][]{
                //Note: recal tables were created using GATK3.4 with one change from 2.87 to 2.88 in expected.CEUTrio.HiSeq.WGS.b37.ch20.1m-1m1k.NA12878.recal.txt
                // The reason is that GATK4 uses a multiplier in summing doubles in RecalDatum.
                // See MathUtilsUniTest.testAddDoubles for a demonstration how that can change the results.
                // See RecalDatum for explanation of why the multiplier is needed.

                // local computation and files (except for the reference)
                {new BQSRTest(GRCh37Ref, HiSeqBam_chr20, dbSNPb37_chr20, " --joinStrategy SHUFFLE", localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_RECAL)},

                // Currently disabled because BaseRecalibratorSpark can't handle more than 1 knownSites file: https://github.com/broadinstitute/gatk/issues/1085
                // Re-enable once this is fixed.
                //{new BQSRTest(GRCh37Ref, HiSeqBam_chr20, dbSNPb37, "-knownSites " + moreSites, localResources + "expected.CEUTrio.HiSeq.WGS.b37.ch20.1m-1m1k.NA12878.2inputs.recal.txt")},

                {new BQSRTest(GRCh37Ref, HiSeqBam_chr20, dbSNPb37_chr20, " --joinStrategy SHUFFLE --indels_context_size 4",  localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_INDELS_CONTEXT_SIZE_4_RECAL)},
                {new BQSRTest(GRCh37Ref, HiSeqBam_chr20, dbSNPb37_chr20, " --joinStrategy SHUFFLE --low_quality_tail 5",     localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_LOW_QUALITY_TAIL_5_RECAL)},
                {new BQSRTest(GRCh37Ref, HiSeqBam_chr20, dbSNPb37_chr20, " --joinStrategy SHUFFLE --quantizing_levels 6",    localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_QUANTIZING_LEVELS_6_RECAL)},
                {new BQSRTest(GRCh37Ref, HiSeqBam_chr20, dbSNPb37_chr20, " --joinStrategy SHUFFLE --mismatches_context_size 4", localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_MISMATCHES_CONTEXT_SIZE_4_RECAL)},
                //// //{new BQSRTest(b36Reference, origQualsBam, dbSNPb36, "-OQ", getResourceDir() + "expected.originalQuals.1kg.chr1.1-1K.1RG.dictFix.OQ.txt")},
        };
    }

    @DataProvider(name = "BQSRTestBucket")
    public Object[][] createBQSRTestDataBucket() {
        final String GRCh37Ref = ReferenceAPISource.URL_PREFIX + ReferenceAPISource.GRCH37_REF_ID;
        final String localResources =  getResourceDir();
        final String HiSeqBamCloud_chr20 = getCloudInputs() + WGS_B37_CH20_1M_1M1K_BAM;
        final String dbSNPb37_chr20 = localResources + DBSNP_138_B37_CH20_1M_1M1K_VCF;

        return new Object[][]{
                // input in cloud, computation local.
                {new BQSRTest(GRCh37Ref, HiSeqBamCloud_chr20, dbSNPb37_chr20, " --joinStrategy SHUFFLE", localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_RECAL)},
        };
    }

    @DataProvider(name = "BQSRLocalRefTest")
    public Object[][] createBQSRLocalRefTestData() {
        final String GRCh37Ref2bit_chr2021 = b37_2bit_reference_20_21;
        final String hiSeqBam_chr20 = getResourceDir() + WGS_B37_CH20_1M_1M1K_BAM;
        final String dbSNPb37_chr20 = getResourceDir() + DBSNP_138_B37_CH20_1M_1M1K_VCF;

        return new Object[][]{
                // input local, computation local.
                {new BQSRTest(GRCh37Ref2bit_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy BROADCAST", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_RECAL)},
                {new BQSRTest(GRCh37Ref2bit_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy BROADCAST --indels_context_size 4", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_INDELS_CONTEXT_SIZE_4_RECAL)},
                {new BQSRTest(GRCh37Ref2bit_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy BROADCAST --low_quality_tail 5", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_LOW_QUALITY_TAIL_5_RECAL)},
                {new BQSRTest(GRCh37Ref2bit_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy BROADCAST --quantizing_levels 6", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_QUANTIZING_LEVELS_6_RECAL)},
                {new BQSRTest(GRCh37Ref2bit_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy BROADCAST --mismatches_context_size 4", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_MISMATCHES_CONTEXT_SIZE_4_RECAL)},
        };
    }

    @Test(dataProvider = "BQSRLocalRefTest")
    public void testBQSRLocalRef(BQSRTest params) throws IOException {
        ArgumentsBuilder ab = new ArgumentsBuilder().add(params.getCommandLineNoApiKey());
        IntegrationTestSpec spec = new IntegrationTestSpec(
                ab.getString(),
                Arrays.asList(params.expectedFileName));
        spec.executeTest("testBQSR-" + params.args, this);
    }

    @DataProvider(name = "BQSRLocalRefTestShuffle")
    public Object[][] createBQSRLocalRefTestDataForShuffle() {
        final String GRCh37Ref_chr2021 = b37_reference_20_21;
        final String hiSeqBam_chr20 = getResourceDir() + WGS_B37_CH20_1M_1M1K_BAM;
        final String dbSNPb37_chr20 = getResourceDir() + DBSNP_138_B37_CH20_1M_1M1K_VCF;

        return new Object[][]{
                // input local, computation local.
                {new BQSRTest(GRCh37Ref_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy SHUFFLE", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_RECAL)},
                {new BQSRTest(GRCh37Ref_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy SHUFFLE --indels_context_size 4", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_INDELS_CONTEXT_SIZE_4_RECAL)},
                {new BQSRTest(GRCh37Ref_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy SHUFFLE --low_quality_tail 5", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_LOW_QUALITY_TAIL_5_RECAL)},
                {new BQSRTest(GRCh37Ref_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy SHUFFLE --quantizing_levels 6", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_QUANTIZING_LEVELS_6_RECAL)},
                {new BQSRTest(GRCh37Ref_chr2021, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy SHUFFLE --mismatches_context_size 4", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_MISMATCHES_CONTEXT_SIZE_4_RECAL)},

        };
    }

    @Test(dataProvider = "BQSRLocalRefTestShuffle")
    public void testBQSRLocalRefShuffle(BQSRTest params) throws IOException {
        ArgumentsBuilder ab = new ArgumentsBuilder().add(params.getCommandLineNoApiKey());
        IntegrationTestSpec spec = new IntegrationTestSpec(
                ab.getString(),
                Arrays.asList(params.expectedFileName));
        spec.executeTest("testBQSR-" + params.args, this);
    }

    @Test
    public void testBlowUpOnBroadcastIncompatibleReference() throws IOException {
        //this should blow up because broadcast requires a 2bit reference
        final String hiSeqBam_chr20 = getResourceDir() + WGS_B37_CH20_1M_1M1K_BAM;
        final String dbSNPb37_chr20 = getResourceDir() + DBSNP_138_B37_CH20_1M_1M1K_VCF;

        BQSRTest params = new BQSRTest(b37_reference_20_21, hiSeqBam_chr20, dbSNPb37_chr20, "--joinStrategy BROADCAST", getResourceDir() + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_RECAL);

        ArgumentsBuilder ab = new ArgumentsBuilder().add(params.getCommandLineNoApiKey());
        IntegrationTestSpec spec = new IntegrationTestSpec(
                ab.getString(),
                1,
                UserException.Require2BitReferenceForBroadcast.class);
        spec.executeTest("testBQSR-" + params.args, this);
    }

    // "local", but we're still getting the reference from the cloud.
    @Test(dataProvider = "BQSRTest", groups = {"cloud"})
    public void testBQSRLocal(BQSRTest params) throws IOException {
        ArgumentsBuilder ab = new ArgumentsBuilder().add(params.getCommandLine());
        IntegrationTestSpec spec = new IntegrationTestSpec(
                ab.getString(),
                Arrays.asList(params.expectedFileName));
        spec.executeTest("testBQSR-" + params.args, this);
    }

    // TODO: re-enable once ReadsSparkSource natively supports files in GCS buckets
    @Test(dataProvider = "BQSRTestBucket", groups = {"bucket"}, enabled = false)
    public void testBQSRBucket(BQSRTest params) throws IOException {
        ArgumentsBuilder ab = new ArgumentsBuilder().add(params.getCommandLine());
        IntegrationTestSpec spec = new IntegrationTestSpec(
                ab.getString(),
                Arrays.asList(params.expectedFileName));
        spec.executeTest("testBQSR-" + params.args, this);
    }

    // TODO: This test is disabled because a new expected result needs to be created.
    @Test(description = "This is to test https://github.com/broadinstitute/hellbender/issues/322", groups = {"cloud"}, enabled = false)
    public void testPlottingWorkflow() throws IOException {
        final String resourceDir = getTestDataDir() + "/" + "BQSR" + "/";
        final String GRCh37Ref = ReferenceAPISource.GRCH37_REF_ID; // that's the "full" version
        final String dbSNPb37_chr2021 = resourceDir + DBSNP_138_B37_CH20_1M_1M1K_VCF;
        final String HiSeqBam_chr20 = getResourceDir() + WGS_B37_CH20_1M_1M1K_BAM;

        final File actualHiSeqBam_recalibrated = createTempFile("actual.recalibrated", ".bam");

        final String tablePre = createTempFile("gatk4.pre.cols", ".table").getAbsolutePath();
        final String argPre = " -R " + ReferenceAPISource.URL_PREFIX + GRCh37Ref + " -knownSites " + dbSNPb37_chr2021 + " -I " + HiSeqBam_chr20
                + " -O " + tablePre + " --sort_by_all_columns true" + " --apiKey " + getGCPTestApiKey();
        new BaseRecalibratorSpark().instanceMain(Utils.escapeExpressions(argPre));

        final String argApply = "-I " + HiSeqBam_chr20 + " --bqsr_recal_file " + tablePre + " -O " + actualHiSeqBam_recalibrated.getAbsolutePath() + " --apiKey " + getGCPTestApiKey();
        new ApplyBQSRSpark().instanceMain(Utils.escapeExpressions(argApply));

        final File actualTablePost = createTempFile("gatk4.post.cols", ".table");
        final String argsPost = " -R " + ReferenceAPISource.URL_PREFIX + GRCh37Ref + " -knownSites " + dbSNPb37_chr2021 + " -I " + actualHiSeqBam_recalibrated.getAbsolutePath()
                + " -O " + actualTablePost.getAbsolutePath() + " --sort_by_all_columns true" + " --apiKey " + getGCPTestApiKey();
        new BaseRecalibratorSpark().instanceMain(Utils.escapeExpressions(argsPost));

        final File expectedHiSeqBam_recalibrated = new File(resourceDir + "expected.NA12878.chr17_69k_70k.dictFix.recalibrated.DIQ.bam");

        SamAssertionUtils.assertSamsEqual(actualHiSeqBam_recalibrated, expectedHiSeqBam_recalibrated, ValidationStringency.LENIENT);

        final File expectedTablePost = new File(getResourceDir() + "expected.NA12878.chr17_69k_70k.postRecalibrated.txt");
        IntegrationTestSpec.assertEqualTextFiles(actualTablePost, expectedTablePost);
    }

    @Test(groups = {"cloud"})
    public void testBQSRFailWithoutDBSNP() throws IOException {
        final String resourceDir =  getTestDataDir() + "/" + "BQSR" + "/";
        final String localResources =  getResourceDir();

        final String GRCh37Ref = ReferenceAPISource.URL_PREFIX + ReferenceAPISource.GRCH37_REF_ID; // that's the "full" version
        final String HiSeqBam_chr17 = resourceDir + "NA12878.chr17_69k_70k.dictFix.bam";

        final String  NO_DBSNP = "";
        final BQSRTest params = new BQSRTest(GRCh37Ref, HiSeqBam_chr17, NO_DBSNP, "", localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_RECAL);
        IntegrationTestSpec spec = new IntegrationTestSpec(
                params.getCommandLine(),
                1,
                UserException.CommandLineException.class);
        spec.executeTest("testBQSRFailWithoutDBSNP", this);
    }

    @Test(groups = {"cloud"})
    public void testBQSRFailWithIncompatibleReference() throws IOException {
        final String resourceDir =  getTestDataDir() + "/" + "BQSR" + "/";
        final String localResources =  getResourceDir();

        final String hg19Ref = ReferenceAPISource.URL_PREFIX + ReferenceAPISource.HG19_REF_ID;
        final String HiSeqBam_chr17 = resourceDir + "NA12878.chr17_69k_70k.dictFix.bam";

        final String dbSNPb37_chr2021 = resourceDir + DBSNP_138_B37_CH20_1M_1M1K_VCF;
        final BQSRTest params = new BQSRTest(hg19Ref, HiSeqBam_chr17, dbSNPb37_chr2021, "", localResources + BQSRTestData.EXPECTED_WGS_B37_CH20_1M_1M1K_RECAL);
        IntegrationTestSpec spec = new IntegrationTestSpec(
                params.getCommandLine(),
                1,
                UserException.IncompatibleSequenceDictionaries.class);
        spec.executeTest("testBQSRFailWithIncompatibleReference", this);
    }
}