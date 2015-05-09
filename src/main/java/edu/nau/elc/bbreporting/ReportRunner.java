package edu.nau.elc.bbreporting;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import edu.nau.elc.bbreporting.reports.COESignatureAssignmentReport;
import edu.nau.elc.bbreporting.reports.ForceCompletionReport;
import edu.nau.elc.bbreporting.reports.Report;
import edu.nau.elc.bbreporting.reports.StaleCoursesReport;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses command-line input and runs selected report. Need to refactor to use configuration file for non-password items.
 */
public class ReportRunner {

    private static Session session;

    private static Logger log = LoggerFactory.getLogger(ReportRunner.class);
    private static Pattern termCodePattern = Pattern.compile("1[0-9]{2}[1478]");

    // this method has become very deeply nested and would be a good candidate for a refactor
    public static void main(String[] args) throws ClassNotFoundException {

        // begin parsing CLI options
        OptionParser parser = new OptionParser();

        // optional SSH options & arguments
        OptionSpec<String> sshUserSpec = parser.accepts("ssh-user", "(optional) Username for SSH proxy.").withRequiredArg();
        OptionSpec<String> sshPassSpec = parser.accepts("ssh-pass", "(optional) Password for SSH proxy.").withRequiredArg();
        OptionSpec<String> sshHostSpec = parser.accepts("ssh-host", "(optional) Address for SSH proxy.").withRequiredArg();
        OptionSpec<Integer> sshPortSpec = parser.accepts("ssh-port", "(optional) SSH port on host (default: 22).")
                .withRequiredArg().ofType(Integer.class);
        OptionSpec<Integer> localPortSpec = parser.accepts("ssh-local-port", "(optional) Local SSH tunnel port (default: 1521).")
                .withRequiredArg().ofType(Integer.class);

        // required OpenDB connection options & arguments
        OptionSpec<String> dbUserSpec = parser.accepts("db-user", "Username for OpenDB.").withRequiredArg();
        OptionSpec<String> dbPassSpec = parser.accepts("db-pass", "Password for OpenDB.").withRequiredArg();
        OptionSpec<String> dbHostSpec = parser.accepts("db-host", "Address for OpenDB server.").withRequiredArg();
        OptionSpec<Integer> dbPortSpec = parser.accepts("db-port", "Port to connect to OpenDB.")
                .withRequiredArg().ofType(Integer.class);

        // required report directory option & argument
        OptionSpec<File> reportDirSpec = parser.accepts("report-dir", "Directory to save report to.")
                .withRequiredArg().ofType(File.class);

        // report options

        // stale course report doesn't get a spec because it doesn't have an argument
        // we could always add an N days option to define staleness
        String staleCourseReportOption = "report-stale-courses";
        parser.accepts(staleCourseReportOption,
                "Find non-SIS courses which haven't been accessed in over a year.");

        OptionSpec<Integer> forceCompletionTermSpec = parser.accepts("report-force-completion",
                "Find tests in a term with Force Completion. Requires 4-digit term code.")
                .withRequiredArg().ofType(Integer.class);

        OptionSpec<Integer> signatureAssignmentTermSpec = parser.accepts("report-signature-assignments",
                "Find rubrics and signature assignments in COE courses. Requires 4-digit term code.")
                .withRequiredArg().ofType(Integer.class);

        parser.accepts("help").forHelp();

        OptionSet opts = parser.parse(args);


        try {
            if (opts.has("help")) {
                parser.printHelpOn(System.out);
                return;
            }

            // make sure all required options are declared and have arguments
            boolean hasDBUser = opts.has(dbUserSpec) && opts.hasArgument(dbUserSpec);
            boolean hasDBPass = opts.has(dbPassSpec) && opts.hasArgument(dbPassSpec);
            boolean hasDBHost = opts.has(dbHostSpec) && opts.hasArgument(dbHostSpec);
            boolean hasDBPort = opts.has(dbPortSpec) && opts.hasArgument(dbPortSpec);
            boolean hasReportDir = opts.has(reportDirSpec) && opts.hasArgument(reportDirSpec);

            // if everything is declared, we'll assume they're valid (tool is for internal use)
            if (hasDBUser && hasDBPass && hasDBHost && hasDBPort && hasReportDir) {

                File reportsFolder = reportDirSpec.value(opts);

                String openDbUser = dbUserSpec.value(opts);
                String openDbPass = dbPassSpec.value(opts);
                String dbHost = dbHostSpec.value(opts);
                int dbPort = dbPortSpec.value(opts);

                // after retreiving the required arguments, let's create a default JDBC connection string
                String connectionString = "jdbc:oracle:thin:@// " + dbHost + ":" + dbPort + "/ORACLE";

                // are we going to use the ssh tunnel? can only do it if these three options are declared with args
                boolean sshTunnel =
                        opts.has(sshUserSpec) && opts.hasArgument(sshUserSpec) &&
                                opts.has(sshPassSpec) && opts.hasArgument(sshPassSpec) &&
                                opts.has(sshHostSpec) && opts.hasArgument(sshHostSpec);

                // check to make sure that if we aren't tunneling that the user didn't try to
                // if they tried to but failed to include all info, let's exit and give them a chance rather than
                // hammering the db server's firewall
                if (!sshTunnel && (
                        opts.has(sshUserSpec) || opts.hasArgument(sshUserSpec) ||
                                opts.has(sshPassSpec) || opts.hasArgument(sshPassSpec) ||
                                opts.has(sshHostSpec) || opts.hasArgument(sshHostSpec)
                )) {
                    log.error("Incomplete SSH options! Exiting.");
                    return;
                }

                if (sshTunnel) {
                    String sshUser = sshUserSpec.value(opts);
                    String sshPass = sshPassSpec.value(opts);
                    String sshHost = sshHostSpec.value(opts);

                    // change if defined on CLI, otherwise default values are used
                    int sshPort = opts.has(sshPortSpec) ? sshPortSpec.value(opts) : 22;
                    int localPort = opts.has(localPortSpec) ? localPortSpec.value(opts) : 1521;

                    // create the SSH tunnel
                    doSshTunnel(sshHost, sshPort, sshUser, sshPass, dbHost, dbPort, localPort);

                    log.info("SSH tunnel created.");

                    // change the connection from a remote one (as defined above) to a local one that points to the tunnel
                    connectionString = "jdbc:oracle:thin:@// localhost:" + localPort + "/ORACLE";
                }

                // check to make sure we're on the classpath -- gradle is looking for this under libs/ojdbc7.jar
                Class.forName("oracle.jdbc.driver.OracleDriver");

                // create the connection
                Connection connection = DriverManager.getConnection(connectionString, openDbUser, openDbPass);

                String nowStamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace(":", "-");

                // pick which report we're going to run
                if (opts.has(staleCourseReportOption)) {

                    log.info("Running stale course report, no other reports will be run.");

                    // setup the file we'll write to
                    File lastAccessReport = new File(reportsFolder.getAbsolutePath() + File.separator
                            + "stale_course_report_" + nowStamp + ".tsv");

                    // run the report
                    // upon reflection, this interface is silly.
                    Report activity = new StaleCoursesReport();
                    activity.runReport(connection, lastAccessReport);

                    log.info("Stale course report done.");

                } else if (opts.has(forceCompletionTermSpec)) {

                    if (opts.hasArgument(forceCompletionTermSpec)) {
                        log.info("Running force completion report, no other reports will be run.");
                        int term = forceCompletionTermSpec.value(opts);

                        Matcher matcher = termCodePattern.matcher("" + term);

                        if (matcher.matches()) {
                            File forceCompletionReportFile = new File(reportsFolder.getAbsolutePath() + File.separator
                                    + "force_completion_report_" + term + "_" + nowStamp + ".tsv");

                            Report forceCompletes = new ForceCompletionReport(term);
                            forceCompletes.runReport(connection, forceCompletionReportFile);

                            log.info("Force Completion report done.");
                        } else {
                            log.error("Force completion report not run -- term must be 4 digits in correct format.");
                        }
                    } else {
                        log.error("No term code provided for force completion report. Exiting.");
                    }

                } else if (opts.has(signatureAssignmentTermSpec)) {

                    if (opts.hasArgument(signatureAssignmentTermSpec)) {

                        log.info("Running signature assignment report, no other reports will be run.");

                        int term = signatureAssignmentTermSpec.value(opts);
                        Matcher matcher = termCodePattern.matcher("" + term);

                        if (matcher.matches()) {

                            // setup the report file
                            File signatureAssignmentReportFile = new File(reportsFolder.getAbsolutePath() + File.separator
                                    + "signature_assignment_report_" + term + "_" + nowStamp + ".tsv");

                            // run the report!
                            Report signatureAssignments = new COESignatureAssignmentReport(term);
                            signatureAssignments.runReport(connection, signatureAssignmentReportFile);

                            log.info("Signature assignment report done.");
                        } else {
                            log.error("Signature Assignment report not run -- term must be 4 digits in correct format.");
                        }
                    } else {
                        log.error("No term code provided for signature assignment report. Exiting.");
                    }

                } else {
                    log.error("No report selected to run. Exiting.");
                }

                log.info("Closing DB connection...");

                // wrap it all up
                connection.close();

                // only close SSH if we made it in the first place
                if (sshTunnel) {
                    log.info("Closing SSH proxy...");
                    session.disconnect();
                }

                log.info("Connections closed. Exiting.");
            } else {

                // if we don't have everything we need, barf
                String missingVals = "Missing required arguments:";

                if (!hasDBHost) missingVals += " db-host ";
                if (!hasDBPort) missingVals += " db-port ";
                if (!hasDBUser) missingVals += " db-user ";
                if (!hasDBPass) missingVals += " db-pass ";
                if (!hasReportDir) missingVals += " report-dir ";

                log.error(missingVals + ". No reports run.");
            }
        } catch (JSchException jse) {
            log.error("Error establishing SSH tunnel.", jse);
        } catch (SQLException sqe) {
            log.error("SQL error.", sqe);
        } catch (IOException ioe) {
            log.error("IO error.", ioe);
        }
    }

    /**
     * Create an SSH tunnel from localhost:local-port to remotehost:remote-port by way of ssh-host:ssh-port.
     * Tunnel resides in private field {@code session} so we can close the tunnel later.
     *
     * @param sshServer       Address of the SSH server.
     * @param sshPort         Port of sshd on the SSH server.
     * @param sshUser         Username for the SSH server.
     * @param sshPass         Password for the SSH server.
     * @param remoteHost      Remote host to connect to.
     * @param remotePort      Remote host port to connect to.
     * @param localTunnelPort Local port to tunnel through.
     * @throws JSchException
     */
    private static void doSshTunnel(String sshServer, int sshPort, String sshUser, String sshPass, String remoteHost, int remotePort, int localTunnelPort) throws JSchException {
        final JSch jsch = new JSch();
        session = jsch.getSession(sshUser, sshServer, sshPort);
        session.setPassword(sshPass);

        final Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        session.setConfig(config);

        session.connect();
        session.setPortForwardingL(localTunnelPort, remoteHost, remotePort);
        log.info("SSH Tunnel successfully configured.");
    }
}
