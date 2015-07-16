__author__ = 'adam'

import configparser
import os
import logging
import re
import time

import cx_Oracle

import nau_bb_reporting.ssh_tunnel as ssh
import nau_bb_reporting.housekeeping as housekeeping
import nau_bb_reporting.reports.stale_courses as stale_courses
import nau_bb_reporting.reports.force_completion as force_completion
import nau_bb_reporting.reports.hardlinks as hardlinks
import nau_bb_reporting.reports.mediafiles as mediafiles
import nau_bb_reporting.reports.librarymovies as librarymovies
import nau_bb_reporting.reports.orphanedinternal as orphanedinternal





# parse arguments
args = housekeeping.parse_parameters()

# read configuration
config = configparser.ConfigParser()

config.read(args['config'])
log_file = config['LOG'].get('file', 'nau-bb-reporting.log')

housekeeping.create_root_logger(log_file)
log = logging.getLogger('nau_bb_reporting.reporter')
log.debug("Parameters: %s", args)

# remaining configuration
report_directory = config['PATHS']['report_dir'] + os.sep

db_conf = config['OPENDB']
db_host = db_conf['host']
db_port = int(db_conf['port'])
db_user = db_conf['user']
db_pass = db_conf['pass']

ssh_conf = config['SSH PROXY']
ssh_host = ssh_conf['host']
ssh_port = int(ssh_conf.get('port', 22))
local_port = int(ssh_conf.get('local_port', 1521))
ssh_user = ssh_conf['user']
ssh_pass = ssh_conf['pass']

# validate configuration
if db_host is None or db_port is None or db_user is None or db_pass is None:
    log.error("OpenDB credentials not provided! Exiting...")
    exit(6)

if report_directory is None:
    log.error("No report directory provided! Exiting...")
    exit(7)

# fire up SSH tunnel
using_ssh_tunnel = ssh_host is not None and ssh_user is not None and ssh_pass is not None
tunnel = None
if using_ssh_tunnel:
    tunnel = ssh.start_tunnel(ssh_host=ssh_host, ssh_port=ssh_port, local_port=local_port,
                              ssh_user=ssh_user, ssh_pass=ssh_pass,
                              remote_host=db_host, remote_port=db_port)
    while not ssh.tunnel_active():
        time.sleep(0.5)

# fire up Oracle
dsn = cx_Oracle.makedsn('localhost', local_port, 'ORACLE') if using_ssh_tunnel \
    else cx_Oracle.makedsn(db_host, db_port, 'ORACLE')
db = cx_Oracle.connect(db_user, db_pass, dsn)
log.info("Database connected.")

# start preparing items which apply to most/all reports

# validate term argument
term = args['term']
p = re.compile('[1][0-9]{2}[1478]')
if term is not None and p.match(term) is None:
    log.error("Invalid term code provided! Exiting...")
    exit(5)

# generate timestamp for reports
timestamp = time.strftime('%Y-%m-%d_%H%M%S')

# find which report is needed
report = args['report']
greedy = args['greedy']
if report == 'stale-courses':
    # run stale courses report
    report_path = report_directory + os.sep + 'stale-courses-' + term + '-' + timestamp + '.xls'
    stale_courses.run(connection=db, out_file_path=report_path)

elif report == 'force-completion':
    if term is None:
        log.error("Trying to run force completion report, but no term provided! Exiting...")
        exit(8)

    report_path = report_directory + os.sep + 'force-completion-' + term + '-' + timestamp + '.xls'
    force_completion.run(term=term, connection=db, out_file_path=report_path)

elif report == 'hardlinks':
    if term is None:
        log.error("Trying to run hardlinks report, but no term provided! Exiting...")
        exit(9)

    report_type = 'greedy-' if greedy else 'lazy-'
    report_path = report_directory + os.sep + 'hardlinks-' + report_type + term + '-' + timestamp + '.xls'
    hardlinks.run(term=term, connection=db, out_file_path=report_path, greedy=greedy)

elif report == 'mediafiles':
    if term is None:
        log.error("Cannot run media files report, no term was provided! Exiting...")
        exit(10)

    media_config = config['MEDIA FILES']

    report_path = report_directory + os.sep + 'mediafiles-' + term + '-' + timestamp + '.xls'

    mediafiles.run(term=term, connection=db, out_file_path=report_path, threshold=media_config['mb_threshold'],
                   pattern=media_config['filename_pattern'])

elif report == 'librarymovies':
    if term is None:
        log.error('Cannor run library movies report, no term was provided! Exiting...')
        exit(11)

    report_path = report_directory + os.sep + 'librarymovies-' + term + '-' + timestamp + '.xls'

    librarymovies.run(term, db, report_path)

elif report == 'orphaned-internal':
    report_path = report_directory + os.sep + 'orphaned-internal-' + timestamp + '.xls'

    orphanedinternal.run(db, report_path)

# close all connections
db.close()
log.info("Database connection disconnected.")
if using_ssh_tunnel:
    ssh.stop_tunnel()
    log.info("SSH Tunnel disconnected.")

log.info('Exiting...\n\n')
