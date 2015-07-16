__author__ = 'adam'
import logging
import argparse


def create_root_logger(log_file):
    # setup logger
    parent_logger = logging.getLogger('nau_bb_reporting')
    parent_logger.setLevel(logging.DEBUG)

    fh = logging.FileHandler(log_file)
    fh.setLevel(logging.DEBUG)

    ch = logging.StreamHandler()
    ch.setLevel(logging.INFO)

    formatter = logging.Formatter('%(asctime)s %(name)s^%(levelname)s: %(message)s')
    fh.setFormatter(formatter)
    ch.setFormatter(formatter)

    parent_logger.addHandler(fh)
    parent_logger.addHandler(ch)


def parse_parameters():
    argparser = argparse.ArgumentParser(description='Bb Learn reporting tool from Northern Arizona University.')
    argparser.add_argument('--config', required=True, help='Path to ini file.', metavar='FILE')
    argparser.add_argument('--term', help='4-digit term code to run report against. '
                                          'Needed for almost all reports.',
                           metavar='TERMCODE')
    argparser.add_argument('report',
                           choices=['force-completion', 'hardlinks', 'stale-courses', 'mediafiles', 'librarymovies',
                                    'orphaned-internal'],
                           help='Which report to run. Make sure to use the --term flag where needed.')
    argparser.add_argument('--greedy', action='store_true',
                           help='Increases level of detail for the hardlinks report. Ignored otherwise.')
    return vars(argparser.parse_args())


def rows_to_dict_list(cursor):
    columns = [i[0] for i in cursor.description]
    return [dict(zip(columns, row)) for row in cursor]
