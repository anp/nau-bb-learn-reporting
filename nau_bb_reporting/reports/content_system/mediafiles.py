__author__ = 'adam'

import logging
import urllib.parse
import re

import easywebdav

from nau_bb_reporting.housekeeping import rows_to_dict_list

log = logging.getLogger('nau_bb_reporting.reports.content_system.mediafiles')

course_id_query = """
SELECT DISTINCT
cm.COURSE_ID
FROM BBLEARN.COURSE_MAIN cm
WHERE cm.COURSE_ID LIKE :course_id_like
"""

media_file_pattern = re.compile(
    '^(.+)\.(aiff|wav|flac|m4a|wma|mp3|aac|ra|rm|aup|mid|3gp|avi|flv|m1v|m2v|fla|m4v|mkv|mov|mpeg|mpg|swf|wmv)$')


def run(webdav_host, webdav_user, webdav_pass, connection, out_file_path, term):
    log.info('Querying database for %s course IDs...', term)
    course_id_like = term + '-NAU00-%'
    cur = connection.cursor()
    cur.prepare(course_id_query)

    cur.execute(None, course_id_like=course_id_like)
    course_ids = [result['COURSE_ID'] for result in rows_to_dict_list(cur)]
    log.info('%s %s course IDs found.', len(course_ids), term)

    log.info('Connecting to WebDAV server...')
    courses_processed = 0
    for course_id in course_ids:
        wd_conn = easywebdav.connect(webdav_host, username=webdav_user, password=webdav_pass,
                                     verify_ssl=True, protocol='https')
        for file in get_media_files(course_id, wd_conn):
            print(str(file))

        courses_processed += 1
        if courses_processed % 100 == 0:
            log.info('%s/%s courses processed.', courses_processed, len(course_ids))


def get_media_files(course_id, connection):
    course_root_dir = '/bbcswebdav/courses/' + course_id
    connection.cd(course_root_dir)
    print(connection.cwd)
    course_files = convert_webdav_tree_to_file_list(connection, course_root_dir, course_root_dir)

    bad_files = [f for f in course_files if f['size'] > 50000000 or media_file_pattern.match(f['file']) is not None]

    return bad_files


def convert_webdav_tree_to_file_list(connection, course_root, parent):
    root_listing = connection.ls()
    file_list = []
    for item in root_listing:
        if item.name.endswith(connection.cwd):
            continue

        relative_path = urllib.parse.urlsplit(item.name).path
        if item.size == 0 and item.contenttype == '':
            connection.cd(relative_path)
            file_list.extend(convert_webdav_tree_to_file_list(connection, course_root, connection.cwd))
            connection.cd(parent)
        else:
            local_path = urllib.parse.unquote(relative_path.replace(course_root + '/', ''))
            size = int(item.size)
            type = item.contenttype

            item_dict = {'file': local_path, 'size': size, 'filetype': type}
            file_list.append(item_dict)

    return file_list
