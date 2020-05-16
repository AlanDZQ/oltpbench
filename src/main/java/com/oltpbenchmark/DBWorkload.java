/******************************************************************************
 *  Copyright 2015 by OLTPBenchmark Project                                   *
 *                                                                            *
 *  Licensed under the Apache License, Version 2.0 (the "License");           *
 *  you may not use this file except in compliance with the License.          *
 *  You may obtain a copy of the License at                                   *
 *                                                                            *
 *    http://www.apache.org/licenses/LICENSE-2.0                              *
 *                                                                            *
 *  Unless required by applicable law or agreed to in writing, software       *
 *  distributed under the License is distributed on an "AS IS" BASIS,         *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  *
 *  See the License for the specific language governing permissions and       *
 *  limitations under the License.                                            *
 ******************************************************************************/


package com.oltpbenchmark;

import com.oltpbenchmark.api.BenchmarkModule;
import com.oltpbenchmark.api.TransactionType;
import com.oltpbenchmark.api.TransactionTypes;
import com.oltpbenchmark.api.Worker;
import com.oltpbenchmark.types.DatabaseType;
import com.oltpbenchmark.util.*;
import org.apache.commons.cli.*;
import org.apache.commons.collections4.map.ListOrderedMap;
import org.apache.commons.configuration2.HierarchicalConfiguration;
import org.apache.commons.configuration2.XMLConfiguration;
import org.apache.commons.configuration2.builder.FileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.convert.LegacyListDelimiterHandler;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.tree.ImmutableNode;
import org.apache.commons.configuration2.tree.xpath.XPathExpressionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class DBWorkload {
    private static final Logger LOG = LoggerFactory.getLogger(DBWorkload.class);

    private static final String SINGLE_LINE = StringUtil.repeat("=", 70);

    private static final String RATE_DISABLED = "disabled";
    private static final String RATE_UNLIMITED = "unlimited";

    /**
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Exception {

        // create the command line parser
        CommandLineParser parser = new PosixParser();

        XMLConfiguration pluginConfig = buildConfiguration("config/plugin.xml");

        Options options = buildOptions(pluginConfig);

        CommandLine argsLine = parser.parse(options, args);

        if (argsLine.hasOption("h")) {
            printUsage(options);
            return;
        } else if (argsLine.hasOption("c") == false) {
            LOG.error("Missing Configuration file");
            printUsage(options);
            return;
        } else if (argsLine.hasOption("b") == false) {
            LOG.error("Missing Benchmark Class to load");
            printUsage(options);
            return;
        }


        // Seconds
        int intervalMonitor = 0;
        if (argsLine.hasOption("im")) {
            intervalMonitor = Integer.parseInt(argsLine.getOptionValue("im"));
        }

        // -------------------------------------------------------------------
        // GET PLUGIN LIST
        // -------------------------------------------------------------------

        String targetBenchmarks = argsLine.getOptionValue("b");

        String[] targetList = targetBenchmarks.split(",");
        List<BenchmarkModule> benchList = new ArrayList<>();

        // Use this list for filtering of the output
        List<TransactionType> activeTXTypes = new ArrayList<>();

        String configFile = argsLine.getOptionValue("c");

        XMLConfiguration xmlConfig = buildConfiguration(configFile);

        // Load the configuration for each benchmark
        int lastTxnId = 0;
        for (String plugin : targetList) {
            String pluginTest = "[@bench='" + plugin + "']";

            // ----------------------------------------------------------------
            // BEGIN LOADING WORKLOAD CONFIGURATION
            // ----------------------------------------------------------------

            WorkloadConfiguration wrkld = new WorkloadConfiguration();
            wrkld.setBenchmarkName(plugin);
            wrkld.setXmlConfig(xmlConfig);

            boolean scriptRun = false;
            if (argsLine.hasOption("t")) {
                scriptRun = true;
                String traceFile = argsLine.getOptionValue("t");
                wrkld.setTraceReader(new TraceReader(traceFile));
                if (LOG.isDebugEnabled()) {
                    LOG.debug(wrkld.getTraceReader().toString());
                }
            }

            // Pull in database configuration
            wrkld.setDBType(DatabaseType.get(xmlConfig.getString("type")));
            wrkld.setDBDriver(xmlConfig.getString("driver"));
            wrkld.setDBConnection(xmlConfig.getString("url"));
            wrkld.setDBUsername(xmlConfig.getString("username"));
            wrkld.setDBPassword(xmlConfig.getString("password"));
            wrkld.setDBBatchSize(xmlConfig.getInt("batchsize", 128));
            wrkld.setDBPoolSize(xmlConfig.getInt("poolsize", 12));

            int terminals = xmlConfig.getInt("terminals[not(@bench)]", 0);
            terminals = xmlConfig.getInt("terminals" + pluginTest, terminals);
            wrkld.setTerminals(terminals);

            if (xmlConfig.containsKey("loaderThreads")) {
                int loaderThreads = xmlConfig.getInt("loaderThreads");
                wrkld.setLoaderThreads(loaderThreads);
            }

            String isolationMode = xmlConfig.getString("isolation[not(@bench)]", "TRANSACTION_SERIALIZABLE");
            wrkld.setIsolationMode(xmlConfig.getString("isolation" + pluginTest, isolationMode));
            wrkld.setScaleFactor(xmlConfig.getDouble("scalefactor", 1.0));
            wrkld.setRecordAbortMessages(xmlConfig.getBoolean("recordabortmessages", false));
            wrkld.setDataDir(xmlConfig.getString("datadir", "."));

            double selectivity = -1;
            try {
                selectivity = xmlConfig.getDouble("selectivity");
                wrkld.setSelectivity(selectivity);
            } catch (NoSuchElementException nse) {
                // Nothing to do here !
            }

            // ----------------------------------------------------------------
            // CREATE BENCHMARK MODULE
            // ----------------------------------------------------------------

            String classname = pluginConfig.getString("/plugin[@name='" + plugin + "']");

            if (classname == null) {
                throw new ParseException("Plugin " + plugin + " is undefined in config/plugin.xml");
            }

            BenchmarkModule bench = ClassUtil.newInstance(classname, new Object[]{wrkld}, new Class<?>[]{WorkloadConfiguration.class});
            Map<String, Object> initDebug = new ListOrderedMap<>();
            initDebug.put("Benchmark", String.format("%s {%s}", plugin.toUpperCase(), classname));
            initDebug.put("Configuration", configFile);
            initDebug.put("Type", wrkld.getDBType());
            initDebug.put("Driver", wrkld.getDBDriver());
            initDebug.put("URL", wrkld.getDBConnection());
            initDebug.put("Pool Size", wrkld.getDBPoolSize());
            initDebug.put("Isolation", wrkld.getIsolationString());
            initDebug.put("Scale Factor", wrkld.getScaleFactor());

            if (selectivity != -1) {
                initDebug.put("Selectivity", selectivity);
            }

            LOG.info("{}\n\n{}", SINGLE_LINE, StringUtil.formatMaps(initDebug));
            LOG.info(SINGLE_LINE);

            // ----------------------------------------------------------------
            // LOAD TRANSACTION DESCRIPTIONS
            // ----------------------------------------------------------------
            int numTxnTypes = xmlConfig.configurationsAt("transactiontypes" + pluginTest + "/transactiontype").size();
            if (numTxnTypes == 0 && targetList.length == 1) {
                //if it is a single workload run, <transactiontypes /> w/o attribute is used
                pluginTest = "[not(@bench)]";
                numTxnTypes = xmlConfig.configurationsAt("transactiontypes" + pluginTest + "/transactiontype").size();
            }
            wrkld.setNumTxnTypes(numTxnTypes);

            List<TransactionType> ttypes = new ArrayList<>();
            ttypes.add(TransactionType.INVALID);
            int txnIdOffset = lastTxnId;
            for (int i = 1; i <= wrkld.getNumTxnTypes(); i++) {
                String key = "transactiontypes" + pluginTest + "/transactiontype[" + i + "]";
                String txnName = xmlConfig.getString(key + "/name");

                // Get ID if specified; else increment from last one.
                int txnId = i;
                if (xmlConfig.containsKey(key + "/id")) {
                    txnId = xmlConfig.getInt(key + "/id");
                }

                TransactionType tmpType = bench.initTransactionType(txnName, txnId + txnIdOffset);

                // Keep a reference for filtering
                activeTXTypes.add(tmpType);

                // Add a ref for the active TTypes in this benchmark
                ttypes.add(tmpType);
                lastTxnId = i;
            } // FOR

            // Wrap the list of transactions and save them
            TransactionTypes tt = new TransactionTypes(ttypes);
            wrkld.setTransTypes(tt);
            LOG.debug("Using the following transaction types: {}", tt);

            // Read in the groupings of transactions (if any) defined for this
            // benchmark
            int numGroupings = xmlConfig.configurationsAt("transactiontypes" + pluginTest + "/groupings/grouping").size();
            LOG.debug("Num groupings: {}", numGroupings);
            for (int i = 1; i < numGroupings + 1; i++) {
                String key = "transactiontypes" + pluginTest + "/groupings/grouping[" + i + "]";

                // Get the name for the grouping and make sure it's valid.
                String groupingName = xmlConfig.getString(key + "/name").toLowerCase();
                if (!groupingName.matches("^[a-z]\\w*$")) {
                    LOG.error(String.format("Grouping name \"%s\" is invalid." + " Must begin with a letter and contain only" + " alphanumeric characters.", groupingName));
                    System.exit(-1);
                } else if (groupingName.equals("all")) {
                    LOG.error("Grouping name \"all\" is reserved." + " Please pick a different name.");
                    System.exit(-1);
                }

                // Get the weights for this grouping and make sure that there
                // is an appropriate number of them.
                List<String> groupingWeights = xmlConfig.getList(String.class, key + "/weights");
                if (groupingWeights.size() != numTxnTypes) {
                    LOG.error(String.format("Grouping \"%s\" has %d weights," + " but there are %d transactions in this" + " benchmark.", groupingName, groupingWeights.size(), numTxnTypes));
                    System.exit(-1);
                }

                LOG.debug("Creating grouping with name, weights: {}, {}", groupingName, groupingWeights);
            }


            benchList.add(bench);

            // ----------------------------------------------------------------
            // WORKLOAD CONFIGURATION
            // ----------------------------------------------------------------

            int size = xmlConfig.configurationsAt("/works/work").size();
            for (int i = 1; i < size + 1; i++) {
                final HierarchicalConfiguration<ImmutableNode> work = xmlConfig.configurationAt("works/work[" + i + "]");
                List<String> weight_strings;

                // use a workaround if there multiple workloads or single
                // attributed workload
                if (targetList.length > 1 || work.containsKey("weights[@bench]")) {
                    weight_strings = work.getList(String.class, "weights" + pluginTest);
                } else {
                    weight_strings = work.getList(String.class, "weights[not(@bench)]");
                }

                int rate = 1;
                boolean rateLimited = true;
                boolean disabled = false;
                boolean serial = false;
                boolean timed = false;

                // can be "disabled", "unlimited" or a number
                String rate_string;
                rate_string = work.getString("rate[not(@bench)]", "");
                rate_string = work.getString("rate" + pluginTest, rate_string);
                if (rate_string.equals(RATE_DISABLED)) {
                    disabled = true;
                } else if (rate_string.equals(RATE_UNLIMITED)) {
                    rateLimited = false;
                } else if (rate_string.isEmpty()) {
                    LOG.error(String.format("Please specify the rate for phase %d and workload %s", i, plugin));
                    System.exit(-1);
                } else {
                    try {
                        rate = Integer.parseInt(rate_string);
                        if (rate < 1) {
                            LOG.error("Rate limit must be at least 1. Use unlimited or disabled values instead.");
                            System.exit(-1);
                        }
                    } catch (NumberFormatException e) {
                        LOG.error(String.format("Rate string must be '%s', '%s' or a number", RATE_DISABLED, RATE_UNLIMITED));
                        System.exit(-1);
                    }
                }
                Phase.Arrival arrival = Phase.Arrival.REGULAR;
                String arrive = work.getString("@arrival", "regular");
                if (arrive.toUpperCase().equals("POISSON")) {
                    arrival = Phase.Arrival.POISSON;
                }

                // We now have the option to run all queries exactly once in
                // a serial (rather than random) order.
                String serial_string;
                serial_string = work.getString("serial", "false");
                if (serial_string.equals("true")) {
                    serial = true;
                } else if (serial_string.equals("false")) {
                    serial = false;
                } else {
                    LOG.error("Serial string should be either 'true' or 'false'.");
                    System.exit(-1);
                }

                // We're not actually serial if we're running a script, so make
                // sure to suppress the serial flag in this case.
                serial = serial && (wrkld.getTraceReader() == null);

                int activeTerminals;
                activeTerminals = work.getInt("active_terminals[not(@bench)]", terminals);
                activeTerminals = work.getInt("active_terminals" + pluginTest, activeTerminals);
                // If using serial, we should have only one terminal
                if (serial && activeTerminals != 1) {
                    LOG.warn("Serial ordering is enabled, so # of active terminals is clamped to 1.");
                    activeTerminals = 1;
                }
                if (activeTerminals > terminals) {
                    LOG.error(String.format("Configuration error in work %d: " + "Number of active terminals is bigger than the total number of terminals", i));
                    System.exit(-1);
                }

                int time = work.getInt("/time", 0);
                int warmup = work.getInt("/warmup", 0);
                timed = (time > 0);
                if (scriptRun) {
                    LOG.info("Running a script; ignoring timer, serial, and weight settings.");
                } else if (!timed) {
                    if (serial) {
                        LOG.info("Timer disabled for serial run; will execute" + " all queries exactly once.");
                    } else {
                        LOG.error("Must provide positive time bound for" + " non-serial executions. Either provide" + " a valid time or enable serial mode.");
                        System.exit(-1);
                    }
                } else if (serial) {
                    LOG.info("Timer enabled for serial run; will run queries" + " serially in a loop until the timer expires.");
                }
                if (warmup < 0) {
                    LOG.error("Must provide nonnegative time bound for" + " warmup.");
                    System.exit(-1);
                }

                wrkld.addWork(time, warmup, rate, weight_strings, rateLimited, disabled, serial, timed, activeTerminals, arrival);
            } // FOR

            // CHECKING INPUT PHASES
            int j = 0;
            for (Phase p : wrkld.getAllPhases()) {
                j++;
                if (p.getWeightCount() != wrkld.getNumTxnTypes()) {
                    LOG.error(String.format("Configuration files is inconsistent, phase %d contains %d weights but you defined %d transaction types", j, p.getWeightCount(), wrkld.getNumTxnTypes()));
                    if (p.isSerial()) {
                        LOG.error("However, note that since this a serial phase, the weights are irrelevant (but still must be included---sorry).");
                    }
                    System.exit(-1);
                }
            } // FOR

            // Generate the dialect map
            wrkld.init();


        }


        // Export StatementDialects
        if (isBooleanOptionSet(argsLine, "dialects-export")) {
            BenchmarkModule bench = benchList.get(0);
            if (bench.getStatementDialects() != null) {
                LOG.info("Exporting StatementDialects for {}", bench);
                String xml = bench.getStatementDialects().export(bench.getWorkloadConfiguration().getDBType(), bench.getProcedures().values());
                LOG.debug(xml);
                System.exit(0);
            }
            throw new RuntimeException("No StatementDialects is available for " + bench);
        }


        // Create the Benchmark's Database
        if (isBooleanOptionSet(argsLine, "create")) {
            for (BenchmarkModule benchmark : benchList) {
                LOG.info("Creating new {} database...", benchmark.getBenchmarkName().toUpperCase());
                runCreator(benchmark);
                LOG.info("Finished creating new {} database...", benchmark.getBenchmarkName().toUpperCase());
            }
        } else  {
            LOG.debug("Skipping creating benchmark database tables");
        }

        // Clear the Benchmark's Database
        if (isBooleanOptionSet(argsLine, "clear")) {
            for (BenchmarkModule benchmark : benchList) {
                LOG.info("Clearing {} database...", benchmark.getBenchmarkName().toUpperCase());
                benchmark.clearDatabase();
                LOG.info("Finished clearing {} database...", benchmark.getBenchmarkName().toUpperCase());
            }
        } else  {
            LOG.debug("Skipping clearing benchmark database tables");
        }

        // Execute Loader
        if (isBooleanOptionSet(argsLine, "load")) {
            for (BenchmarkModule benchmark : benchList) {
                LOG.info("Loading data into {} database...", benchmark.getBenchmarkName().toUpperCase());
                runLoader(benchmark);
                LOG.info("Finished loading data into {} database...", benchmark.getBenchmarkName().toUpperCase());
            }
        } else {
            LOG.debug("Skipping loading benchmark database records");
        }

        // Execute a Script
        if (argsLine.hasOption("runscript")) {
            for (BenchmarkModule benchmark : benchList) {
                String script = argsLine.getOptionValue("runscript");
                LOG.info("Running a SQL script: {}", script);
                runScript(benchmark, script);
                LOG.info("Finished running a SQL script: {}", script);
            }
        }

        // Execute Workload
        if (isBooleanOptionSet(argsLine, "execute")) {
            // Bombs away!
            Results r = null;
            try {
                r = runWorkload(benchList, intervalMonitor);
            } catch (Throwable ex) {
                LOG.error("Unexpected error when running benchmarks.", ex);
                System.exit(1);
            }


            // WRITE OUTPUT
            writeOutputs(r, activeTXTypes, argsLine);

            // WRITE HISTOGRAMS
            if (argsLine.hasOption("histograms")) {
                writeHistograms(r);
            }


        } else {
            LOG.info("Skipping benchmark workload execution");
        }
    }

    private static Options buildOptions(XMLConfiguration pluginConfig) {
        Options options = new Options();
        options.addOption("b", "bench", true, "[required] Benchmark class. Currently supported: " + pluginConfig.getList("/plugin//@name"));
        options.addOption("c", "config", true, "[required] Workload configuration file");
        options.addOption(null, "create", true, "Initialize the database for this benchmark");
        options.addOption(null, "clear", true, "Clear all records in the database for this benchmark");
        options.addOption(null, "load", true, "Load data using the benchmark's data loader");
        options.addOption(null, "execute", true, "Execute the benchmark workload");
        options.addOption(null, "runscript", true, "Run an SQL script");

        options.addOption("h", "help", false, "Print this help");
        options.addOption("s", "sample", true, "Sampling window");
        options.addOption("im", "interval-monitor", true, "Throughput Monitoring Interval in milliseconds");
        options.addOption("ss", false, "Verbose Sampling per Transaction");
        options.addOption("o", "output", true, "Output file (default System.out)");
        options.addOption("d", "directory", true, "Base directory for the result files, default is current directory");
        options.addOption("t", "timestamp", false, "Each result file is prepended with a timestamp for the beginning of the experiment");
        options.addOption("ts", "tracescript", true, "Script of transactions to execute");
        options.addOption(null, "histograms", false, "Print txn histograms");
        options.addOption(null, "dialects-export", true, "Export benchmark SQL to a dialects file");
        options.addOption(null, "output-raw", true, "Output raw data");
        options.addOption(null, "output-samples", true, "Output sample data");
        return options;
    }

    private static XMLConfiguration buildConfiguration(String filename) throws ConfigurationException {

        Parameters params = new Parameters();
        FileBasedConfigurationBuilder<XMLConfiguration> builder = new FileBasedConfigurationBuilder<XMLConfiguration>(XMLConfiguration.class).configure(params.xml().setFileName(filename).setListDelimiterHandler(new LegacyListDelimiterHandler(',')).setExpressionEngine(new XPathExpressionEngine()));
        return builder.getConfiguration();

    }

    private static void writeHistograms(Results r) {
        StringBuilder sb = new StringBuilder();

        sb.append(StringUtil.bold("Completed Transactions:")).append("\n").append(r.getTransactionSuccessHistogram()).append("\n\n");

        sb.append(StringUtil.bold("Aborted Transactions:")).append("\n").append(r.getTransactionAbortHistogram()).append("\n\n");

        sb.append(StringUtil.bold("Rejected Transactions (Server Retry):")).append("\n").append(r.getTransactionRetryHistogram()).append("\n\n");

        sb.append(StringUtil.bold("Unexpected Errors:")).append("\n").append(r.getTransactionErrorHistogram());

        if (r.getTransactionAbortMessageHistogram().isEmpty() == false) {
            sb.append("\n\n").append(StringUtil.bold("User Aborts:")).append("\n").append(r.getTransactionAbortMessageHistogram());
        }

        LOG.info(SINGLE_LINE);
        LOG.info("Workload Histograms:\n{}", sb.toString());
        LOG.info(SINGLE_LINE);
    }


    /**
     * Write out the results for a benchmark run to a bunch of files
     *
     * @param r
     * @param activeTXTypes
     * @param argsLine
     * @throws Exception
     */
    private static void writeOutputs(Results r, List<TransactionType> activeTXTypes, CommandLine argsLine) throws Exception {

        // If an output directory is used, store the information
        String outputDirectory = "results";
        if (argsLine.hasOption("d")) {
            outputDirectory = argsLine.getOptionValue("d");
        }
        String filePrefix = "";
        if (argsLine.hasOption("t")) {
            filePrefix = TimeUtil.getCurrentTime().getTime() + "_";
        }


        // Output target 
        PrintStream ps = null;
        PrintStream rs = null;
        String baseFileName = "oltpbench";
        if (argsLine.hasOption("o")) {
            if (argsLine.getOptionValue("o").equals("-")) {
                ps = System.out;
                rs = System.out;
                baseFileName = null;
            } else {
                baseFileName = argsLine.getOptionValue("o");
            }
        }

        // Build the complex path
        String baseFile = filePrefix;
        String nextName;

        if (baseFileName != null) {
            // Check if directory needs to be created
            if (outputDirectory.length() > 0) {
                FileUtil.makeDirIfNotExists(outputDirectory.split("/"));
            }

            baseFile = filePrefix + baseFileName;

            if (argsLine.getOptionValue("output-raw", "true").equalsIgnoreCase("true")) {
                // RAW OUTPUT
                nextName = FileUtil.getNextFilename(FileUtil.joinPath(outputDirectory, baseFile + ".csv"));
                rs = new PrintStream(new File(nextName));
                LOG.info("Output Raw data into file: {}", nextName);
                r.writeAllCSVAbsoluteTiming(activeTXTypes, rs);
                rs.close();
            }

            if (isBooleanOptionSet(argsLine, "output-samples")) {
                // Write samples using 1 second window
                nextName = FileUtil.getNextFilename(FileUtil.joinPath(outputDirectory, baseFile + ".samples"));
                rs = new PrintStream(new File(nextName));
                LOG.info("Output samples into file: {}", nextName);
                r.writeCSV2(rs);
                rs.close();
            }


        } else if (LOG.isDebugEnabled()) {
            LOG.debug("No output file specified");
        }

        // SUMMARY FILE
        if (argsLine.hasOption("s")) {
            nextName = FileUtil.getNextFilename(FileUtil.joinPath(outputDirectory, baseFile + ".res"));
            ps = new PrintStream(new File(nextName));
            LOG.info("Output into file: {}", nextName);

            int windowSize = Integer.parseInt(argsLine.getOptionValue("s"));
            LOG.info("Grouped into Buckets of {} seconds", windowSize);
            r.writeCSV(windowSize, ps);

            // Allow more detailed reporting by transaction to make it easier to check
            if (argsLine.hasOption("ss")) {

                for (TransactionType t : activeTXTypes) {
                    PrintStream ts = ps;
                    if (ts != System.out) {
                        // Get the actual filename for the output
                        baseFile = filePrefix + baseFileName + "_" + t.getName();
                        nextName = FileUtil.getNextFilename(FileUtil.joinPath(outputDirectory, baseFile + ".res"));
                        ts = new PrintStream(new File(nextName));
                        r.writeCSV(windowSize, ts, t);
                        ts.close();
                    }
                }
            }
        } else {
            LOG.warn("No bucket size specified");
        }

        if (ps != null) {
            ps.close();
        }
        if (rs != null) {
            rs.close();
        }
    }


    private static void runScript(BenchmarkModule bench, String script) {
        LOG.debug(String.format("Running %s", script));
        bench.runScript(script);
    }

    private static void runCreator(BenchmarkModule bench) {
        LOG.debug(String.format("Creating %s Database", bench));
        bench.createDatabase();
    }

    private static void runLoader(BenchmarkModule bench) {
        LOG.debug(String.format("Loading %s Database", bench));
        bench.loadDatabase();
    }

    private static Results runWorkload(List<BenchmarkModule> benchList, int intervalMonitor) throws QueueLimitException, IOException {
        List<Worker<?>> workers = new ArrayList<>();
        List<WorkloadConfiguration> workConfs = new ArrayList<>();
        for (BenchmarkModule bench : benchList) {
            LOG.info("Creating {} virtual terminals...", bench.getWorkloadConfiguration().getTerminals());
            workers.addAll(bench.makeWorkers());
            // LOG.info("done.");

            int num_phases = bench.getWorkloadConfiguration().getNumberOfPhases();
            LOG.info(String.format("Launching the %s Benchmark with %s Phase%s...", bench.getBenchmarkName().toUpperCase(), num_phases, (num_phases > 1 ? "s" : "")));
            workConfs.add(bench.getWorkloadConfiguration());

        }
        Results r = ThreadBench.runRateLimitedBenchmark(workers, workConfs, intervalMonitor);
        LOG.info(SINGLE_LINE);
        LOG.info("Rate limited reqs/s: {}", r);
        return r;
    }

    private static void printUsage(Options options) {
        HelpFormatter hlpfrmt = new HelpFormatter();
        hlpfrmt.printHelp("oltpbenchmark", options);
    }

    /**
     * Returns true if the given key is in the CommandLine object and is set to
     * true.
     *
     * @param argsLine
     * @param key
     * @return
     */
    private static boolean isBooleanOptionSet(CommandLine argsLine, String key) {
        if (argsLine.hasOption(key)) {
            LOG.debug("CommandLine has option '{}'. Checking whether set to true", key);
            String val = argsLine.getOptionValue(key);
            LOG.debug(String.format("CommandLine %s => %s", key, val));
            return (val != null ? val.equalsIgnoreCase("true") : false);
        }
        return (false);
    }
}
