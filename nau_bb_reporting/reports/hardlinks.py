"""
This report looks at all of the course content HTML and tries to find
links that point back to Bb Learn but are not managed by the LMS or CMS
(i.e. they are copypasta from a user who was smart but not smart enough).

In a given term, we get all of the course content HTML, and then begin
ingesting it into BeautifulSoup and grabbing all of the links. The links
are then checked against a bunch of patterns (developed partially in the
abstract but also iteratively over dozens of previous reports) to see
if they're likely candidates.

If "greedy" mode is enabled, we'll also go after all of the deployed
HTML files in a course, as those very frequently contain bad links.
"""


__author__ = 'adam'

import logging
from string import ascii_uppercase

import pandas as pd
from bs4 import BeautifulSoup

log = logging.getLogger('nau_bb_reporting.reports.hardlinks')

# this only gets us all of the HTML with links in a term
# we have to parse and check the HTML locally
lazy_query = """
select
    content_items.course_id,
    cc.main_data
from
    (SELECT DISTINCT
        cm.course_id,
        cc.title,
        cc.pk1
    FROM bblearn.course_main cm, bblearn.course_contents cc
    WHERE
        cc.main_data LIKE '%<a%'
        AND cc.crsmain_pk1 = cm.pk1
        AND cm.course_id LIKE :course_id_like
    ) content_items
  JOIN
bblearn.course_contents cc
ON cc.pk1 = content_items.pk1
"""

# this only gets us the deployed HTML files in a term
# this could be extended to actually fetch the files, but that
# would require a special reporting account with read access
# to all BbL files, and is not a security hole we want to introduce
greedy_query = """
select DISTINCT
  cm.course_id
from bblearn.course_main cm, bblearn.course_contents cc, bblearn.course_contents_files ccf, bblearn.files f
 WHERE
   NOT REGEXP_LIKE(f.link_name,'(DVD|VT|T)[0-9]{2,5}_(.+).html','i')
   AND f.link_name LIKE '%.htm%'
   AND f.pk1 = ccf.files_pk1
   AND ccf.course_contents_pk1 = cc.pk1
   AND cc.available_ind = 'Y'
   AND cc.cnthndlr_handle = 'resource/x-bb-file'
   AND cc.crsmain_pk1 = cm.pk1
   AND cm.course_id LIKE :course_id_like
"""


def run(term, connection, out_file_path, greedy=False):
    log.info("Running hardlinks report for %s.", term)

    course_ids = set()
    found_course_ids = []
    # splitting this query seems to improve performance, as temp tables are kept small
    # and we avoid hitting swap (at least that's my guess as to why)
    course_id_patterns = [term + '-NAU00-' + letter + '%' for letter in ascii_uppercase]

    # first get all the deployed HTML files if needed
    if greedy:
        log.info('Retreiving a list of %s courses with deployed HTML files...', term)
        greedy_cur = connection.cursor()
        greedy_cur.prepare(greedy_query)

        for pattern in course_id_patterns:
            greedy_cur.execute(None, course_id_like=pattern)
            for row in greedy_cur:
                found_course_ids.append((row[0], 'HTML FILE'))

    main_cur = connection.cursor()
    main_cur.prepare(lazy_query)
    log.info('Checking all %s courses for content items with bad links...', term)
    for pattern in course_id_patterns:
        main_cur.execute(None, course_id_like=pattern)
        for row in main_cur:
            course_id = row[0]
            html = row[1]

            # now we check the HTML for bad links...
            found_link = get_first_hardlink(html)
            if course_id not in course_ids and found_link is not None:
                found_course_ids.append((course_id, found_link))
                course_ids.add(course_id)

    log.info('Found all courses, writing to report file.')
    header = ['course id', 'link found']
    df = pd.DataFrame([x for x in found_course_ids], columns=header)
    df.to_excel(out_file_path, encoding='UTF-8', columns=header, index=False)
    log.info('Wrote report to %s', out_file_path)


def get_first_hardlink(html_content):
    soup = BeautifulSoup(html_content)

    # get all of the link urls and the image sources
    urls = [link.get('href') for link in soup.find_all('a')]
    urls.extend([image.get('src') for image in soup.find_all('img')])

    for link in urls:
        # now begin the long fall-through logic
        # if we get to the bottom, the link isn't a problem

        # first we want to make sure we don't want cycles checking empty text
        if link is None or len(link) == 0:
            continue

        trimmed = link.replace('%20', ' ').strip()
        if len(trimmed) == 0:
            continue

        # prep the url for easier conditionals
        url = trimmed.replace(' ', '%20')
        url = url.replace('@X@EmbeddedFile.requestUrlStub@X@', 'https://bblearn.nau.edu/')
        url = url.lower()

        # these don't reference bblearn, but they suck for students
        if 'iris.nau.edu/owa/redir.aspx' in url:
            return url

        elif 'about:blank' == url:
            continue

        # if it's an xid in WebDAV then it's probably OK
        elif 'xid' in url and 'bbcswebdav' in url:
            continue

        # if it points outside of bblearn, it's not really our problem (with the exception of OWA links)
        elif (url.startswith('http://') or url.startswith('https://')) and 'bblearn' not in url:
            continue

        # these are placed by the content editor's smiley tool
        elif '/images/ci/' in url:
            continue

        # if it is definitely pointing to Bb Learn, but isn't of the many tools that legitimately
        # insert links in content items, it's BAD
        elif ('courses' in url or 'webapps' in url or 'bbcswebdav' in url or 'webct' in url or 'vista' in url) \
                and '/institution/' not in url \
                and '/execute/viewdocumentation?' not in url \
                and '/wvms-bb-bblearn' not in url \
                and '/bb-collaborate-bblearn' not in url \
                and '/vtbe-tinymce/tiny_mce' not in url \
                and 'webapps/login' not in url \
                and 'webapps/portal' not in url \
                and 'bbgs-nbc-content-integration-bblearn' not in url \
                and 'bb-selfpeer-bblearn' not in url:
            return url

        # if it doesn't points outside of bblearn, and it doesn't specifically point to bblearn
        # then it's probably a relative link, which we should also burn with fire
        elif not url.startswith('https://') and \
                not url.startswith('http://') and \
                not url.startswith('www') and \
                not url.startswith('javascript:') and \
                not url.startswith('mailto:') and \
                not url.startswith('#') and \
                not url.startswith('data:image/') and \
                        'webapps' not in url and \
                        '.com' not in url and \
                        '.net' not in url and \
                        '.edu' not in url and \
                        '.org' not in url and \
                        'http://cdn.slidesharecdn.com/' not in url:
            return url

    # if we've made it this far, we've checked all urls and found them wanting
    return None
