__author__ = 'adam'

from setuptools import find_packages, setup

setup(name="nau-bb-learn-reporting",
      version="0.1",
      author="Adam Perry",
      author_email='adam.perry@nau.edu',
      platforms=["any"],
      license="MIT",
      url="https://github.com/dikaiosune/nau-bb-learn-reporting",
      packages=find_packages(),
      install_requires=[
          "cx_Oracle>=5.1.3",
          "paramiko>=1.15.2",
          "pandas>=0.16.2",
          "xlwt>=1.0.0",
          "beautifulsoup4>=4.3.2"
      ],
      )
