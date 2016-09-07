/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hazelcast.simulator.coordinator;

import com.hazelcast.simulator.common.SimulatorProperties;
import com.hazelcast.simulator.protocol.connector.CoordinatorRemoteConnector;
import com.hazelcast.simulator.protocol.core.AddressLevel;
import com.hazelcast.simulator.protocol.core.Response;
import com.hazelcast.simulator.protocol.core.SimulatorAddress;
import com.hazelcast.simulator.protocol.operation.RcDownloadOperation;
import com.hazelcast.simulator.protocol.operation.RcInstallOperation;
import com.hazelcast.simulator.protocol.operation.RcPrintLayoutOperation;
import com.hazelcast.simulator.protocol.operation.RcStopCoordinatorOperation;
import com.hazelcast.simulator.protocol.operation.RcTestRunOperation;
import com.hazelcast.simulator.protocol.operation.RcTestStatusOperation;
import com.hazelcast.simulator.protocol.operation.RcTestStopOperation;
import com.hazelcast.simulator.protocol.operation.RcWorkerKillOperation;
import com.hazelcast.simulator.protocol.operation.RcWorkerScriptOperation;
import com.hazelcast.simulator.protocol.operation.RcWorkerStartOperation;
import com.hazelcast.simulator.protocol.operation.SimulatorOperation;
import com.hazelcast.simulator.protocol.registry.TargetType;
import com.hazelcast.simulator.protocol.registry.WorkerQuery;
import com.hazelcast.simulator.utils.CommandLineExitException;
import com.hazelcast.simulator.utils.FileUtils;
import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.log4j.Logger;

import java.io.Closeable;
import java.io.File;
import java.util.List;

import static com.hazelcast.simulator.coordinator.CoordinatorCli.DEFAULT_DURATION_SECONDS;
import static com.hazelcast.simulator.coordinator.CoordinatorCli.getDurationSeconds;
import static com.hazelcast.simulator.utils.CliUtils.initOptionsOnlyWithHelp;
import static com.hazelcast.simulator.utils.CliUtils.initOptionsWithHelp;
import static com.hazelcast.simulator.utils.CommonUtils.closeQuietly;
import static com.hazelcast.simulator.utils.CommonUtils.exitWithError;
import static com.hazelcast.simulator.utils.FileUtils.fileAsText;
import static java.lang.String.format;

/**
 * todo:
 * - when invalid version is used in install; no proper feedback
 * - if there are no workers, don't show a stacktrace.
 * com.hazelcast.simulator.utils.CommandLineExitException: No workers running!
 * at com.hazelcast.simulator.protocol.registry.ComponentRegistry.getFirstWorker(ComponentRegistry.java:182)
 * at com.hazelcast.simulator.coordinator.RemoteClient.invokeOnTestOnFirstWorker(RemoteClient.java:93)
 * at com.hazelcast.simulator.coordinator.TestCaseRunner.executePhase(TestCaseRunner.java:198)
 * <p>
 */
public class CoordinatorRemoteCli implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(CoordinatorRemoteCli.class);

    private final SimulatorProperties simulatorProperties;
    private final int coordinatorPort;
    private final String[] args;
    private CoordinatorRemoteConnector connector;

    public CoordinatorRemoteCli(String[] args) {
        this.args = args;
        this.simulatorProperties = new SimulatorProperties();
        File file = new File(FileUtils.getUserDir(), "simulator.properties");
        if (file.exists()) {
            simulatorProperties.init(file);
        }

        this.coordinatorPort = simulatorProperties.getCoordinatorPort();
        if (coordinatorPort == 0) {
            throw new CommandLineExitException("Coordinator port is disabled!");
        }
    }

    @SuppressWarnings("checkstyle:cyclomaticcomplexity")
    public void run() {
        if (args.length == 0) {
            printHelpAndExit();
        }

        String cmd = args[0];
        String[] subArgs = removeFirst(args);

        connector = new CoordinatorRemoteConnector("localhost", coordinatorPort);
        connector.start();
        if ("download".equals(cmd)) {
            new DownloadCli().run(subArgs);
        } else if ("install".equals(cmd)) {
            new InstallCli().run(subArgs);
        } else if ("print-layout".equals(cmd)) {
            new PrintClusterLayoutCli().run(subArgs);
        } else if ("stop".equals(cmd)) {
            new ExitCli().run(subArgs);
        } else if ("test-run".equals(cmd)) {
            new TestRunCli().run(subArgs);
        } else if ("test-start".equals(cmd)) {
            new TestStartCli().run(subArgs);
        } else if ("test-status".equals(cmd)) {
            new TestStatusCli().run(subArgs);
        } else if ("test-stop".equals(cmd)) {
            new TestStopCli().run(subArgs);
        } else if ("worker-kill".equals(cmd)) {
            new WorkerKill().run(subArgs);
        } else if ("worker-script".equals(cmd)) {
            new WorkerScriptCli().run(subArgs);
        } else if ("worker-start".equals(cmd)) {
            new WorkerStartCli().run(subArgs);
        } else {
            printHelpAndExit();
        }
    }

    private static void printHelpAndExit() {
        System.out.println(
                "Command         Description                                                                 \n"
                        + "--------------------------                                                                  \n"
                        + "download        Downloads all artifacts from the workers                                    \n"
                        + "install         Installs vendor software on the remote machines                             \n"
                        + "print-layout    Prints the cluster-layout                                                   \n"
                        + "test-run        Runs a test and wait for completion                                         \n"
                        + "test-start      Starts a test asynchronously                                                \n"
                        + "test-stop       Stops a test                                                                \n"
                        + "test-status     Checks the status of a test                                                 \n"
                        + "stop            Stops the Coordinator remote session                                        \n"
                        + "worker-kill     Kills one or more workers                                                   \n"
                        + "worker-script   Executes a script on workers                                                \n"
                        + "worker-start    Starts workers                                                              ");

        System.exit(1);
    }

    @Override
    public void close() {
        closeQuietly(connector);
    }

    public static void main(String[] args) throws Exception {
        CoordinatorRemoteCli cli = null;
        try {
            cli = new CoordinatorRemoteCli(args);
            cli.run();
        } catch (Exception e) {
            exitWithError(LOGGER, "Failed to run Coordinator", e);
        } finally {
            closeQuietly(cli);
        }
    }

    private static String[] removeFirst(String[] args) {
        String[] result = new String[args.length - 1];
        System.arraycopy(args, 1, result, 0, args.length - 1);
        return result;
    }

    private abstract class AbstractCli {

        protected final OptionParser parser = new OptionParser();

        protected OptionSet options;

        protected abstract SimulatorOperation newOperation();

        protected abstract OptionSet newOptions(String[] args);

        protected void run(String[] args) {
            this.options = newOptions(args);

            Response response = connector.write(newOperation());

            Response.Part errorPart = response.getFirstErrorPart();

            if (errorPart == null) {
                Response.Part part = response.getFirstPart();
                System.out.println(part.getPayload() == null ? "success" : part.getPayload());
            } else {
                if (errorPart.getPayload() != null) {
                    System.err.println(errorPart.getPayload());
                } else {
                    System.err.print("errorType:" + errorPart.getType());
                }
                throw new CommandLineExitException(
                        format("Could not process command: %s message [%s]", errorPart.getType(), errorPart.getPayload()));
            }
        }
    }

    private class InstallCli extends AbstractCli {
        private final String help
                = "The 'install' command installs Hazelcast on the agents. By default the coordinator will upload to\n"
                + "the agents what has been configured on the simulator.properties. But in case of testing multiple\n"
                + "versions the other versions need to be installed. If a worker is started without the right software\n"
                + "the worker will fail to start\n"
                + "\n"
                + "In case of the maven version spec, the local repository is always preferred and no update checking\n"
                + "is done in case of SNAPSHOT version. This is very useful for testing custom SNAPSHOT branches, but\n"
                + "can be a problem if the local SNAPSHOT is not updated and the user expects to use the latest\n"
                + "SNAPSHOT.\n"
                + "\n"
                + "Examples\n"
                + "# installs Hazelcast 3.6 from local or remote repo.\n"
                + "coordinator-remote install maven=3.6\n\n"
                + "# installs Hazelcast 3.8-SNAPSHOT from local or remote repo.\n"
                + "coordinator-remote install maven=3.8-SNAPSHOT\n\n"
                + "# installs Hazelcast using some git commit hash.\n"
                + "coordinator-remote install git=<somehash>\n";

        private final NonOptionArgumentSpec<String> argumentSpec = parser
                .nonOptions("version specification").ofType(String.class);

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            List<String> nonOptionArguments = options.valuesOf(argumentSpec);
            if (nonOptionArguments.size() != 1) {
                throw new CommandLineExitException("Too many arguments");
            }

            String versionSpec = nonOptionArguments.get(0);
            LOGGER.info("Installing [" + versionSpec + "]");
            return new RcInstallOperation(args[0]);
        }
    }

    private class TestStatusCli extends AbstractCli {
        private final String help =
                "Returns the status of a test"
                        + "\n"
                        + "Examples\n"
                        + "# Checks the status of some test.\n"
                        + "coordinator-remote test-status C_A*_W*_T1\n";

        private final NonOptionArgumentSpec<String> argumentSpec = parser
                .nonOptions("test address").ofType(String.class);

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            List<String> nonOptionArguments = options.valuesOf(argumentSpec);
            if (nonOptionArguments.size() != 1) {
                throw new CommandLineExitException("Too many arguments");
            }

            String testId = nonOptionArguments.get(0);
            return new RcTestStatusOperation(testId);
        }
    }

    private class TestStopCli extends AbstractCli {
        private final String help =
                "Ask a test to stop its warmup or running phase. It is especially useful for tests that run without a duration."
                        + "\n"
                        + "Examples\n"
                        + "# Stops a test.\n"
                        + "coordinator-remote test-stop C_A*_W*_T1\n";

        private final NonOptionArgumentSpec<String> argumentSpec = parser
                .nonOptions("test address").ofType(String.class);

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            List<String> nonOptionArguments = options.valuesOf(argumentSpec);
            if (nonOptionArguments.size() != 1) {
                throw new CommandLineExitException("Too many arguments");
            }

            String testId = nonOptionArguments.get(0);
            return new RcTestStopOperation(testId);
        }
    }

    private class DownloadCli extends AbstractCli {
        private final String help = ""
                + "The download command downloads all artifacts from the workers.\n";

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsOnlyWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            return new RcDownloadOperation();
        }
    }

    private class WorkerScriptCli extends AbstractCli {
        private final String help
                = "The 'worker-script' commands executes a Bash-script or Javascript on workers\n"
                + "\n"
                + "Various filter options are available like --versionSpec, --workerType, --agent, --worker\n"
                + "\n"
                + "By default the script is executed on all members selected, but can be controlled using the\n"
                + "--maxCount setting.\n"
                + "\n"
                + "By default the selection of members is deterministic, however using the --random setting\n"
                + "one can enable shuffling of members."
                + "\n"
                + "The bash and javascript commands are very useful for High Availability testing by e.g\n"
                + "causing split brains, consuming most memory, consuming CPU cycles, and poking in Hazelcast\n"
                + "internals\n"
                + "\n"
                + "Examples\n"
                + "# takes a threadump on all workers\n"
                + "coordinator-remote worker-script  --command 'bash:jstack $PID''\n\n"
                + "# takes a threadump on at most 2 workers\n"
                + "coordinator-remote worker-script  --maxCount 2 --command 'bash:jstack $PID''\n\n"
                + "# takes a threadump on all member\n"
                + "coordinator-remote worker-script  --workerType member --command 'bash:jstack $PID''\n\n"
                + "# takes a threadump on all workers with a specific version\n"
                + "coordinator-remote worker-script  --versionSpec maven=3.7 --command 'bash:jstack $PID''\n\n"
                + "# takes a threaddump on all member on agent C_A1\n"
                + "coordinator-remote worker-script --workerType member --agent C_A1 --command 'bash:jstack $PID'\n\n"
                + "# takes a threaddump on C_A1_W1\n"
                + "coordinator-remote worker-script --worker C_A1_W1 --command 'bash:jstack $PID'\n\n"
                + "# executes a javascript on all workers\n"
                + "coordinator-remote worker-script --command 'js:java.lang.System.out.println(\"hello\")'";

        private final OptionSpec<String> versionSpecSpec = parser.accepts("versionSpec",
                "The versionSpec of the worker to select e.g  maven=3.7 or git=master")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<String> workerTypeSpec = parser.accepts("workerType",
                "The type of machine to select, e.g. member, litemember, javaclient (native clients will be added soon) etc")
                .withRequiredArg().ofType(String.class).defaultsTo("member");

        private final OptionSpec<Integer> maxCountSpec = parser.accepts("maxCount",
                "The maximum number of workers to select. It can safely be called with a maxCount larger than "
                        + "the actual number of workers.")
                .withRequiredArg().ofType(Integer.class);

        private final OptionSpec<String> agentSpec = parser.accepts("agent",
                "The simulator address of the agent owning the worker")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<String> workerSpec = parser.accepts("worker",
                "The simulator address of the worker. This is useful if you want to execute a script on a particular worker.")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<Boolean> randomSpec = parser.accepts("random",
                "If workers should be picked randomly or predictably")
                .withRequiredArg().ofType(Boolean.class).defaultsTo(false);

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            List<?> nonOptionArguments = options.nonOptionArguments();
            if (nonOptionArguments.size() != 1) {
                throw new CommandLineExitException("Only 1 argument allowed. Use single quotes, e.g. 'bash:jstack $PID'");
            }

            String agent = loadAgentAddress(options, agentSpec);
            String worker = loadWorkerAddress(options, workerSpec);
            if (agent != null && worker != null) {
                throw new CommandLineExitException("---agent and --worker can't both be set");
            }

            Integer maxCount = options.valueOf(maxCountSpec);
            if (maxCount != null && maxCount <= 0) {
                throw new CommandLineExitException("--maxCount can't be smaller than 1");
            }

            WorkerQuery workerQuery = new WorkerQuery()
                    .setAgentAddress(agent)
                    .setWorkerAddress(worker)
                    .setWorkerType(options.valueOf(workerTypeSpec))
                    .setVersionSpec(options.valueOf(versionSpecSpec))
                    .setMaxCount(maxCount)
                    .setRandom(options.valueOf(randomSpec));

            String cmd = (String) nonOptionArguments.get(0);
            LOGGER.info("Executing [" + cmd + "]");
            return new RcWorkerScriptOperation(cmd, workerQuery);
        }
    }

    private class PrintClusterLayoutCli extends AbstractCli {
        private final String help
                = "Prints the cluster layout on the coordinator.\n";

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsOnlyWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            return new RcPrintLayoutOperation();
        }
    }

    private class ExitCli extends AbstractCli {
        private final String help
                = "Terminates the the coordinator session.\n";

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsOnlyWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            LOGGER.info("Shutting down Coordinator Remote");
            return new RcStopCoordinatorOperation();
        }
    }

    private class WorkerKill extends AbstractCli {
        private final String help
                = "The 'worker-kill' command kills one or more workers. The killing can be done based using an exact\n"
                + "worker address or using various filters like versionSpec, etc.\n"
                + "\n"
                + "By default the selection of members is deterministic, however using the --randomSpec setting\n"
                + "one can enable shuffling of members."
                + "\n"
                + "By default the worker is killed using a System.exit call, however one can also use a bash script\n"
                + "or a javascript which is executed inside the JMV\n"
                + "\n"
                + "If a script is used, the big difference between the 'worker-script' and the 'worker-kill' command\n"
                + "is when the 'worker-script' kills the JVM, it will lead to a failure. With the 'worker-kill' the\n"
                + "failure is expected and the call will wait till the worker has actually died.\n"
                + "\n"
                + "Examples\n"
                + "# kills one member using System.exit(0)\n"
                + "coordinator-remote worker-kill\n\n"
                + "# kill 2 java clients\n"
                + "coordinator-remote worker-kill --maxCount 2 --workerType javaclient\n\n"
                + "# kills 3 litemembers using version spec git=master clients\n"
                + "coordinator-remote worker-kill --maxCount 3 --workerType litemember --versionSpec git=master\n\n"
                + "# kills 1 member on agent C_A1\n"
                + "coordinator-remote worker-kill --agent C_A1 \n\n"
                + "# kills member C_A1_W1\n"
                + "coordinator-remote worker-kill --worker C_A1_W1 \n\n"
                + "# kill one member using OOME\n"
                + "coordinator-remote worker-kill --command OOME \n\n"
                + "# kill one member using bash command kill -9\n"
                + "coordinator-remote worker-kill --command 'bash:kill -9 $PID'\n\n"
                + "# kill one member using javascript which calls System.exit\n"
                + "coordinator-remote worker-kill --command 'js:java.lang.System.exit(0);'";

        private final OptionSpec<String> versionSpecSpec = parser.accepts("versionSpec",
                "The versionSpec of the member to kill, e.g. maven=3.7. The default value is null, meaning that the versionSpec"
                        + "is not part of the selection criteria ")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<String> workerTypeSpec = parser.accepts("workerType",
                "The type of machine to kill. member, litemember, client:java (native clients will be added soon) etc")
                .withRequiredArg().ofType(String.class).defaultsTo("member");

        private final OptionSpec<Integer> maxCountSpec = parser.accepts("maxCount",
                "The maximum number of workers to kill. It can safely be called with a maxCount larger than "
                        + "the actual number of workers.")
                .withRequiredArg().ofType(Integer.class).defaultsTo(1);

        private final OptionSpec<String> agentSpec = parser.accepts("agent",
                "The simulator address of the agent owning the worker to kill")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<String> workerSpec = parser.accepts("worker",
                "The simulator address of the worker to kill")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<String> commandSpec = parser.accepts("command",
                "The way to kill the worker. E.g. 'System.exit', 'OOME', 'bash:kill -9 $PID', 'js:somescript")
                .withRequiredArg().ofType(String.class).defaultsTo("System.exit");

        private final OptionSpec<Boolean> randomSpec = parser.accepts("random",
                "If workers should be picked randomly or predictably")
                .withRequiredArg().ofType(Boolean.class).defaultsTo(false);

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsOnlyWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            int maxCount = options.valueOf(maxCountSpec);
            if (maxCount <= 0) {
                throw new CommandLineExitException("--maxCount can't be smaller than 1");
            }

            String agentAddress = loadAgentAddress(options, agentSpec);
            String workerAddress = loadWorkerAddress(options, workerSpec);
            if (agentAddress != null && workerAddress != null) {
                throw new CommandLineExitException("--agent and --worker can't both be set");
            }

            WorkerQuery workerQuery = new WorkerQuery()
                    .setAgentAddress(agentAddress)
                    .setWorkerAddress(workerAddress)
                    .setWorkerType(options.valueOf(workerTypeSpec))
                    .setVersionSpec(options.valueOf(versionSpecSpec))
                    .setMaxCount(maxCount)
                    .setRandom(options.valueOf(randomSpec));

            String command = options.valueOf(commandSpec);
            if ("System.exit".equals(command)) {
                command = "js:java.lang.System.exit(0);";
            } else if ("OOME".equals(command)) {
                command = "var list = new java.util.ArrayList();\n"
                        + "while(true){\n"
                        + "    try{"
                        + "         list.add( new Array(10000));"
                        + "    }catch(error){}"
                        + "}";
            }
            return new RcWorkerKillOperation(command, workerQuery);
        }
    }

    private class WorkerStartCli extends AbstractCli {
        private final String help
                = "The 'worker-start' command starts one or more workers.\n"
                + "\n"
                + "Before a test run run, the appropriate workers need to be started using 'worker-starts'\n"
                + "\n"
                + "By default the workers will be spread so that the number of worker on each agent is in balance.\n"
                + "Using the coordinator --dedicatedMemberMachines setting dedicated member agents can be created.\n"
                + "\n"
                + "The worker-starts command will NOT install software when a --versionSpec is used. Make sure that\n"
                + "appropriate calls to the install command have been made easier.\n"
                + "\n"
                + "Examples\n"
                + "# starts 1 members\n"
                + "coordinator-remote worker-start\n\n"
                + "# starts 2 java clients\n"
                + "coordinator-remote worker-start --count 2 --workerType javaclient\n\n"
                + "# starts 3 litemembers using version spec git=master clients\n"
                + "coordinator-remote worker-start --count --workerType litemember --versionSpec git=master\n\n"
                + "# starts 1 member on agent C_A1\n"
                + "coordinator-remote worker-start --agent C_A1 \n\n"
                + "# starts 1 client with a custom client-hazelcast.xml file\n"
                + "coordinator-remote worker-start --config client-hazelcast.xml \n\n";

        private final OptionSpec<String> vmOptionsSpec = parser.accepts("vmOptions",
                "Worker JVM options (quotes can be used).")
                .withRequiredArg().ofType(String.class).defaultsTo("");

        private final OptionSpec<String> versionSpecSpec = parser.accepts("versionSpec",
                "The versionSpec of the member, e.g. maven=3.7. It will default to what is configured in the"
                        + " simulator.properties.")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<String> workerTypeSpec = parser.accepts("workerType",
                "The type of machine to start. member, litemember, javaclient (native clients will be added soon) etc.")
                .withRequiredArg().ofType(String.class).defaultsTo("member");

        private final OptionSpec<Integer> countSpec = parser.accepts("count",
                "The number of workers to start.")
                .withRequiredArg().ofType(Integer.class).defaultsTo(1);

        private final OptionSpec<String> configSpec = parser.accepts("config",
                "The file containing the configuration to use to start up the worker. E.g. Hazelcast configuration.")
                .withRequiredArg().ofType(String.class);

        private final OptionSpec<String> agentSpec = parser.accepts("agent",
                "The simulator address of the agent to start the worker on.")
                .withRequiredArg().ofType(String.class);

        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsOnlyWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            int count = options.valueOf(countSpec);
            if (count <= 0) {
                throw new CommandLineExitException("--count can't be smaller than 1");
            }

            LOGGER.info(format("Starting %s workers", count));

            String hzConfig = null;
            if (options.has(configSpec)) {
                hzConfig = fileAsText(options.valueOf(configSpec));
            }

            return new RcWorkerStartOperation(
                    count,
                    options.valueOf(versionSpecSpec),
                    options.valueOf(vmOptionsSpec),
                    options.valueOf(workerTypeSpec),
                    hzConfig,
                    loadAgentAddress(options, agentSpec));
        }
    }

    private abstract class TestRunStartCli extends AbstractCli {
        protected final OptionSpec<String> durationSpec = parser.accepts("duration",
                "Amount of time to execute the RUN phase per test, e.g. 10s, 1m, 2h or 3d. If duration is set to 0, "
                        + "the test will run until the test decides to stop.")
                .withRequiredArg().ofType(String.class).defaultsTo(format("%ds", DEFAULT_DURATION_SECONDS));

        protected final OptionSpec<String> warmupSpec = parser.accepts("warmup",
                "Amount of time to execute the warmup per test, e.g. 10s, 1m, 2h or 3d. If warmup is set to 0, "
                        + "the test will warmup until the test decides to stop.")
                .withRequiredArg().ofType(String.class);

        protected final OptionSpec<TargetType> targetTypeSpec = parser.accepts("targetType",
                format("Defines the type of Workers which execute the RUN phase."
                        + " The type PREFER_CLIENT selects client Workers if they are available, member Workers otherwise."
                        + " List of allowed types: %s", TargetType.getIdsAsString()))
                .withRequiredArg().ofType(TargetType.class).defaultsTo(TargetType.PREFER_CLIENT);

        protected final OptionSpec<Integer> targetCountSpec = parser.accepts("targetCount",
                "Defines the number of Workers which execute the RUN phase. The value 0 selects all Workers.")
                .withRequiredArg().ofType(Integer.class).defaultsTo(0);

        protected final OptionSpec parallelSpec = parser.accepts("parallel",
                "If defined tests are run in parallel.");

        protected final OptionSpec<Boolean> verifyEnabledSpec = parser.accepts("verify",
                "Defines if tests are verified.")
                .withRequiredArg().ofType(Boolean.class).defaultsTo(true);

        protected final OptionSpec<Boolean> failFastSpec = parser.accepts("failFast",
                "Defines if the TestSuite should fail immediately when a test from a TestSuite fails instead of continuing.")
                .withRequiredArg().ofType(Boolean.class).defaultsTo(true);
    }

    private class TestRunCli extends TestRunStartCli {
        private final String help
                = "The 'test-run' command runs a test suite and waits for its completion.\n"
                + "\n"
                + "A testsuite can contain a single test, or multiple tests when using test4@someproperty=10\n"
                + "By default the test are run in sequential, but can be controlled using the --parallel flag\n"
                + "\n"
                + "By default a test will prefer to run on client and of none available, it will try to run on\n"
                + "The members. Also it will use either all clients or all members as drivers of the test. This\n"
                + "behavior can be controlled using the --targetType and --targetCount options"
                + "\n"
                + "Examples\n"
                + "# runs a file 'test.properties' for 1 minute\n"
                + "coordinator-remote run\n\n"
                + "# runs atomiclong.properties for 1 minute\n"
                + "coordinator-remote run atomiclong.properties\n\n"
                + "# runs a test with a warmup period of 5 minute and a duration of 1 hour\n"
                + "coordinator-remote run --warmup 5m --duration 1h\n\n"
                + "# runs a test by running all tests in the suite in parallel for 10m.\n"
                + "coordinator-remote run --duration 10m --parallel suite.properties\n\n"
                + "# run a test but disable the verification\n"
                + "coordinator-remote run --verify false\n\n"
                + "# run a test but disable the fail fast mechanism\n"
                + "coordinator-remote run --failFast \n\n"
                + "# runs a test on 3 members no matter if there are clients or more than 3 members in the cluster.\n"
                + "coordinator-remote run --targetType member --targetCount 3 \n\n";


        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            List testsuiteFiles = options.nonOptionArguments();
            File testSuiteFile;
            if (testsuiteFiles.size() > 1) {
                throw new CommandLineExitException(format("Too many TestSuite files specified: %s", testsuiteFiles));
            } else if (testsuiteFiles.size() == 1) {
                testSuiteFile = new File((String) testsuiteFiles.get(0));
            } else {
                testSuiteFile = new File("test.properties");
            }

            LOGGER.info("File:" + testSuiteFile);

            TestSuite suite = TestSuite.loadTestSuite(testSuiteFile, "")
                    .setDurationSeconds(getDurationSeconds(options, durationSpec))
                    .setTargetType(options.valueOf(targetTypeSpec))
                    .setTargetCount(options.valueOf(targetCountSpec))
                    .setParallel(options.has(parallelSpec))
                    .setVerifyEnabled(options.valueOf(verifyEnabledSpec))
                    .setFailFast(options.valueOf(failFastSpec));

            if (options.has(warmupSpec)) {
                suite.setWarmupSeconds(getDurationSeconds(options, warmupSpec));
            }

            LOGGER.info("Running testSuite:" + testSuiteFile.getAbsolutePath());
            return new RcTestRunOperation(suite, false);
        }
    }

    private class TestStartCli extends TestRunStartCli {
        private final String help
                = "The 'test=start' command runs a test suite asynchronously and returns the id's of the created tests.\n"
                + "\n"
                + "A testsuite can contain a single test, or multiple tests when using test4@someproperty=10\n"
                + "By default the test are run in sequential, but can be controlled using the --parallel flag\n"
                + "\n"
                + "By default a test will prefer to run on client and of none available, it will try to run on\n"
                + "The members. Also it will use either all clients or all members as drivers of the test. This\n"
                + "behavior can be controlled using the --targetType and --targetCount options"
                + "\n"
                + "Examples\n"
                + "# runs a file 'test.properties' for 1 minute\n"
                + "coordinator-remote run\n\n"
                + "# runs atomiclong.properties for 1 minute\n"
                + "coordinator-remote run atomiclong.properties\n\n"
                + "# runs a test with a warmup period of 5 minute and a duration of 1 hour\n"
                + "coordinator-remote run --warmup 5m --duration 1h\n\n"
                + "# runs a test by running all tests in the suite in parallel for 10m.\n"
                + "coordinator-remote run --duration 10m --parallel suite.properties\n\n"
                + "# run a test but disable the verification\n"
                + "coordinator-remote run --verify false\n\n"
                + "# run a test but disable the fail fast mechanism\n"
                + "coordinator-remote run --failFast \n\n"
                + "# runs a test on 3 members no matter if there are clients or more than 3 members in the cluster.\n"
                + "coordinator-remote run --targetType member --targetCount 3 \n\n";


        @Override
        protected OptionSet newOptions(String[] args) {
            return initOptionsWithHelp(parser, help, args);
        }

        @Override
        protected SimulatorOperation newOperation() {
            List testsuiteFiles = options.nonOptionArguments();
            File testSuiteFile;
            if (testsuiteFiles.size() > 1) {
                throw new CommandLineExitException(format("Too many TestSuite files specified: %s", testsuiteFiles));
            } else if (testsuiteFiles.size() == 1) {
                testSuiteFile = new File((String) testsuiteFiles.get(0));
            } else {
                testSuiteFile = new File("test.properties");
            }

            LOGGER.info("File:" + testSuiteFile);

            TestSuite suite = TestSuite.loadTestSuite(testSuiteFile, "")
                    .setDurationSeconds(getDurationSeconds(options, durationSpec))
                    .setTargetType(options.valueOf(targetTypeSpec))
                    .setTargetCount(options.valueOf(targetCountSpec))
                    .setParallel(options.has(parallelSpec))
                    .setVerifyEnabled(options.valueOf(verifyEnabledSpec))
                    .setFailFast(options.valueOf(failFastSpec));

            if (options.has(warmupSpec)) {
                suite.setWarmupSeconds(getDurationSeconds(options, warmupSpec));
            }

            LOGGER.info("Running testSuite:" + testSuiteFile.getAbsolutePath());
            return new RcTestRunOperation(suite, true);
        }
    }

    private static String loadAgentAddress(OptionSet options, OptionSpec<String> spec) {
        String agentAddress = options.valueOf(spec);
        if (agentAddress != null) {
            SimulatorAddress address;
            try {
                address = SimulatorAddress.fromString(agentAddress);
            } catch (Exception e) {
                throw new CommandLineExitException("Agent address [" + agentAddress
                        + "] is not a valid simulator address", e);
            }

            if (!address.getAddressLevel().equals(AddressLevel.AGENT)) {
                throw new CommandLineExitException("Agent address [" + agentAddress
                        + "] is not a valid agent address, it's a " + address.getAddressLevel() + " address");
            }
        }
        return agentAddress;
    }

    private static String loadWorkerAddress(OptionSet options, OptionSpec<String> spec) {
        String workerAddress = options.valueOf(spec);
        if (workerAddress != null) {
            SimulatorAddress address;
            try {
                address = SimulatorAddress.fromString(workerAddress);
            } catch (Exception e) {
                throw new CommandLineExitException("Worker address [" + workerAddress
                        + "] is not a valid simulator address", e);
            }

            if (!address.getAddressLevel().equals(AddressLevel.WORKER)) {
                throw new CommandLineExitException("Worker address [" + workerAddress
                        + "] is not a valid worker address, it's a " + address.getAddressLevel() + " address");
            }
        }
        return workerAddress;
    }
}