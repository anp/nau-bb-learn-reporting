__author__ = 'adam'

import logging

import pandas as pd

from nau_bb_reporting.housekeeping import rows_to_dict_list

log = logging.getLogger('nau_bb_reporting.reports.mediafiles')

query = """
SELECT
  u.FULL_PATH,
  u.FILE_NAME,
  f.FILE_SIZE,
  f.MIME_TYPE
  from BBLEARN_CMS_DOC.XYF_URLS u, BBLEARN_CMS_DOC.XYF_FILES f
where
  (REGEXP_LIKE(u.FILE_NAME,
               '^(.+)\.(aiff|wav|flac|m4a|wma|mp3|aac|ra|rm|aup|mid|3gp|avi|flv|m1v|m2v|fla|m4v|mkv|mov|mpeg|mpg|swf|wmv)$',
               'i')
   OR (f.file_size > (:mb_threshold * 1000000))
  )
  AND f.MIME_TYPE <> 'trash' AND f.MIME_TYPE IS NOT NULL
  AND u.FILE_ID = f.FILE_ID
  and u.FULL_PATH LIKE '/courses/' || :course_id_like
"""


def run(term, connection, out_file_path):
    log.info("Running media file report for %s.", term)

    course_id_pattern = term + '-NAU00-%'

    cur = connection.cursor()
    cur.prepare(query)
    log.info('Checking all %s courses for media files and other large files...', term)

    cur.execute(None, course_id_like=course_id_pattern, mb_threshold=100)
    db_results = rows_to_dict_list(cur)

    results = []
    for result in db_results:
        course_id = result['FULL_PATH']
        file_name = result['FILE_NAME']
        file_size = round(result['FILE_SIZE'] / 1000000, 1)
        mime_type = result['MIME_TYPE']

        results.append({'FILE PATH': course_id, 'FILENAME': file_name, 'SIZE (MB)': file_size, 'MIME TYPE': mime_type})

    log.info('Found all %s courses and files, writing to report file.', term)

    df = pd.DataFrame(results)
    df.to_excel(out_file_path, sheet_name=term + ' Hardlink courses', encoding='UTF-8', index=False)
    log.info('Wrote report to %s', out_file_path)
