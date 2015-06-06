package edu.nau.elc.bbreporting;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import edu.nau.elc.bbreporting.reports.*;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
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

	private static Logger log = LogManager.getLogger(ReportRunner.class);
	private static Pattern termCodePattern = Pattern.compile("1[0-9]{2}[1478]");

	// this method has become very deeply nested and would be a good candidate for a refactor
	public static void main(String[] args) throws ClassNotFoundException {
		try {

			// begin parsing CLI options
			OptionParser parser = new OptionParser();

			OptionSpec<File> configFileSpec = parser.accepts("config", "Path to properties file.").withRequiredArg().ofType(File.class);

			// report options

			// stale course report doesn't get a spec because it doesn't have an argument
			// we could always add an N days option to define staleness
			parser.accepts("report-stale-courses",
					"Find non-SIS courses which haven't been accessed in over a year.");

			OptionSpec<Integer> forceCompletionTermSpec = parser.accepts("report-force-completion",
					"Find tests in a term with Force Completion. Requires 4-digit term code.")
					.withRequiredArg().ofType(Integer.class);

			OptionSpec<Integer> signatureAssignmentTermSpec = parser.accepts("report-signature-assignments",
					"Find rubrics and signature assignments in COE courses. Requires 4-digit term code.")
					.withRequiredArg().ofType(Integer.class);

			OptionSpec<Integer> hardlinkCoursesTermSpec = parser.accepts("report-hardlink-courses",
					"Find courses that should be checked for hardlinks. Requires 4-digit term code.")
					.withRequiredArg().ofType(Integer.class);

			parser.accepts("greedy", "Flag to make certain reports extra detailed (currently only hardlinks).");

			parser.accepts("help").forHelp();

			OptionSet opts = parser.parse(args);
			if (opts.has("help")) {
				parser.printHelpOn(System.out);
				return;
			}

			Configuration config = new PropertiesConfiguration(configFileSpec.value(opts));

			File reportsFolder = new File(config.getString("reportDir", Paths.get(".").toAbsolutePath().normalize().toString()));

			String openDbUser = config.getString("dbUser");
			String openDbPass = config.getString("dbPass");
			String dbHost = config.getString("dbHost");
			int dbPort = config.getInt("dbPort");

			String sshUser = config.getString("sshUser", null);
			String sshPass = config.getString("sshPass", null);
			String sshHost = config.getString("sshHost", null);
			int sshPort = config.getInt("sshPort", 22);
			int localPort = config.getInt("localPort", 1521);


			// after retreiving the required arguments, let's create a default JDBC connection string
			String connectionString = "jdbc:oracle:thin:@//" + dbHost + ":" + dbPort + "/ORACLE";

			// are we going to use the ssh tunnel? can only do it if these three options are declared with args
			boolean sshTunnel = (sshUser != null) && (sshPass != null) && (sshHost != null);

			// check to make sure that if we aren't tunneling that the user didn't try to
			// if they tried to but failed to include all info, let's exit and give them a chance rather than
			// hammering the db server's firewall
			if (!sshTunnel && (sshUser != null || sshPass != null || sshHost != null)) {
				log.fatal("Incomplete SSH options! Exiting.");
				return;
			}

			if (sshTunnel) {
				// create the SSH tunnel
				doSshTunnel(sshHost, sshPort, sshUser, sshPass, dbHost, dbPort, localPort);

				log.info("SSH tunnel created.");

				// change the connection from a remote one (as defined above) to a local one that points to the tunnel
				connectionString = "jdbc:oracle:thin:@//localhost:" + localPort + "/ORACLE";
			}

			// check to make sure we're on the classpath -- gradle is looking for this under libs/ojdbc7.jar
			Class.forName("oracle.jdbc.driver.OracleDriver");

			// create the connection
			Connection connection = DriverManager.getConnection(connectionString, openDbUser, openDbPass);

			String nowStamp = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace(":", "-");

			// pick which report we're going to run
			if (opts.has("report-stale-courses")) {

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
						log.fatal("Force completion report not run -- term must be 4 digits in correct format.");
					}
				} else {
					log.fatal("No term code provided for force completion report. Exiting.");
				}


			} else if (opts.has(signatureAssignmentTermSpec)) {

				if (!opts.hasArgument(signatureAssignmentTermSpec)) {
					log.fatal("Signature Assignment report not run -- term must be 4 digits in correct format.");
				}

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
					log.fatal("Signature Assignment report not run, term must be in correct format.");
				}


			} else if (opts.has(hardlinkCoursesTermSpec)) {
				log.info("Running hardlink courses report, no other reports will be run.");

				boolean aggressiveHardlinks = opts.has("greedy");
				int term = hardlinkCoursesTermSpec.value(opts);

				if (termCodePattern.matcher(Integer.toString(term)).matches()) {
					// setup the report file
					File hardlinkCoursesReportFile = new File(reportsFolder.getAbsolutePath() + File.separator
							+ "hardlink_courses_report_"
							+ (aggressiveHardlinks ? "aggressive_" : "unaggressive_")
							+ term + "_" + nowStamp + ".txt");

					// run the report!
					Report hardLinkCourses = new HardlinkCoursesReport(term, aggressiveHardlinks);
					hardLinkCourses.runReport(connection, hardlinkCoursesReportFile);

					log.info("Hardlink courses report done.");
				} else {
					log.fatal("Hardlink courses report not run, term code must be in correct format.");
				}


			} else {
				log.fatal("No report (selected|able) to run. Exiting.");
			}

			// wrap it all up
			log.info("Closing DB connection...");
			connection.close();

			// only close SSH if we made it in the first place
			if (sshTunnel) {
				log.info("Closing SSH proxy...");
				session.disconnect();
			}

			log.info("Connections closed. Exiting.");
		} catch (JSchException jse) {
			log.fatal("Error establishing SSH tunnel.", jse);
		} catch (SQLException sqe) {
			log.fatal("SQL error.", sqe);
		} catch (IOException ioe) {
			log.fatal("IO error.", ioe);
		} catch (ConfigurationException ce) {
			log.fatal("Problem with configuration: " + ce.getMessage());
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
