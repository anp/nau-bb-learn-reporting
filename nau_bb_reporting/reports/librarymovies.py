__author__ = 'adam'

import logging

import pandas as pd

log = logging.getLogger('nau_bb_reporting.reports.librarymovies')

query = """
SELECT DISTINCT
  u.FILE_NAME,
  u.FULL_PATH
FROM BBLEARN_CMS_DOC.XYF_URLS u
WHERE
  REGEXP_LIKE(u.FILE_NAME, '^(DVD|VT|T)[0-9]{2,5}_(.+)\.html$', 'c')
  AND u.FULL_PATH LIKE '/courses/' || :term_code  || '-NAU00-%'
"""


def run(term, connection, out_file_path):
    log.info("Running e-reserve file report for %s.", term)

    cur = connection.cursor()
    cur.prepare(query)
    log.info('Checking all %s courses for content items linked to e-reserve files...', term)

    cur.execute(None, term_code=term)
    df = pd.DataFrame([{'file_name': r[0], 'path': r[1]} for r in cur.fetchall()])

    log.info('Found all %s courses and files, writing to report file.', term)

    df.to_excel(out_file_path, encoding='UTF-8', index=False)
    log.info('Wrote report to %s', out_file_path)
