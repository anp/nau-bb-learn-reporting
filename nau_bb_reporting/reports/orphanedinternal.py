__author__ = 'adam'

import logging

import pandas as pd

log = logging.getLogger('nau_bb_reporting.reports.orphanedinternal')

main_query = """
SELECT
  u.FILE_NAME,
  f.FILE_SIZE,
  u.FULL_PATH
FROM BBLEARN_CMS_DOC.XYF_URLS u, BBLEARN_CMS_DOC.XYF_FILES f
WHERE
  u.FILE_NAME NOT IN (SELECT COURSE_ID FROM BBLEARN.COURSE_MAIN)
  AND f.FILE_ID = u.FILE_ID
  AND u.PARENT_ID = (SELECT FILE_ID
                      FROM BBLEARN_CMS_DOC.XYF_URLS
                      WHERE FULL_PATH = '/internal/courses')
ORDER BY f.FILE_SIZE DESC
"""


def run(connection, out_file_path):
    cur = connection.cursor()
    log.info('Checking all courses for orphaned internal course folders...')

    cur.execute(main_query)
    results = [{'course id': c[0], 'size (mb)': round(int(c[1]) / 1000000, 1), 'path': c[2]} for c in cur.fetchall()]

    df = pd.DataFrame(results)
    log.info('Found all orphaned internal folders, writing to report file.')

    df.to_excel(out_file_path, encoding='UTF-8', index=False)
    log.info('Wrote report to %s', out_file_path)
