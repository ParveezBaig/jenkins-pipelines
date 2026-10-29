// Runs five pxc-qa-suites builds in parallel, one per suite set below. A failed child marks
// this build UNSTABLE and the others keep running, so every run finishes and keeps its results.

// The five runs. workers: true takes NUMBER_OF_WORKERS and PARALLEL_INSTANCE_TYPE, otherwise
// the suites run sequentially (0 workers) on INSTANCE_TYPE.
def qaRuns() {
  return [
    [name: 'upgrade',                     suites: 'upgrade',                   encryption: true,  workers: false, upgrade: true],
    [name: 'replication',                 suites: 'replication',               encryption: false, workers: false, upgrade: false],
    [name: 'correctness-galera_sr-ssl',   suites: 'correctness,galera_sr,ssl', encryption: false, workers: true,  upgrade: false],
    [name: 'random_qa',                   suites: 'random_qa',                 encryption: true,  workers: true,  upgrade: false],
    [name: 'loadtest',                    suites: 'loadtest',                  encryption: true,  workers: false, upgrade: false],
  ]
}

// String parameters are trimmed: a stray space breaks git refs and URLs in the child.
def p(String name) {
  return params[name]?.trim() ?: ''
}

def runQaSuites(Map run) {
  def child = build(
    job: 'pxc-qa-suites',
    parameters: [
      string(name: 'PXC_VERSION', value: run.upgrade ? p('UPGRADE_PXC_VERSION') : p('PXC_VERSION')),
      string(name: 'PXC_LOWER_VERSION', value: run.upgrade ? p('PXC_LOWER_VERSION') : ''),
      string(name: 'PXC_RHEL_GLIBC_VERSION', value: p('PXC_RHEL_GLIBC_VERSION')),
      string(name: 'PXC_DEBIAN_GLIBC_VERSION', value: p('PXC_DEBIAN_GLIBC_VERSION')),
      string(name: 'PXC_TARBALL_SOURCE', value: params.PXC_TARBALL_SOURCE),
      string(name: 'QA_SUITES', value: run.suites),
      string(name: 'QA_TESTS', value: ''),
      string(name: 'DISABLED_TESTS', value: p('DISABLED_TESTS')),
      booleanParam(name: 'ENCRYPTION_RUN', value: run.encryption),
      booleanParam(name: 'QA_DEBUG', value: params.QA_DEBUG),
      string(name: 'NUMBER_OF_WORKERS', value: run.workers ? p('NUMBER_OF_WORKERS') : '0'),
      string(name: 'QA_CONFIG_OVERRIDES', value: p('QA_CONFIG_OVERRIDES')),
      string(name: 'PXC_QA_REPO', value: p('PXC_QA_REPO')),
      string(name: 'PXC_QA_BRANCH', value: p('PXC_QA_BRANCH')),
      string(name: 'PSTRESS_BRANCH', value: p('PSTRESS_BRANCH')),
      string(name: 'PERCONA_QA_REPO', value: p('PERCONA_QA_REPO')),
      string(name: 'PERCONA_QA_BRANCH', value: p('PERCONA_QA_BRANCH')),
      string(name: 'TESTING_GIT_ACCOUNT', value: p('TESTING_GIT_ACCOUNT')),
      string(name: 'TESTING_BRANCH', value: p('TESTING_BRANCH')),
      string(name: 'TEST_OS', value: params.TEST_OS),
      string(name: 'INSTANCE_TYPE', value: run.workers ? p('PARALLEL_INSTANCE_TYPE') : p('INSTANCE_TYPE')),
      string(name: 'QA_TIMEOUT_HOURS', value: p('QA_TIMEOUT_HOURS')),
    ],
    propagate: false,
    wait: true
  )
  def summary = "${run.name}: ${child.result} (${child.absoluteUrl})"
  echo summary
  if (child.result != 'SUCCESS') {
    unstable(summary)
  }
  return summary
}

def summaries = [:]

pipeline {
  // Only triggers and waits for the child builds, so it holds no executor.
  agent none
  parameters {
    string(
      name: 'PXC_VERSION',
      defaultValue: 'PXC84',
      description: 'Version tested by every run except upgrade. PXC84 | PXC97 | PXC80 | PXC57 = newest available tarball of that series, or a specific version, used exactly: 8.4.10-10.1'
    )
    string(
      name: 'UPGRADE_PXC_VERSION',
      defaultValue: 'PXC97',
      description: 'Upgrade run: the version upgraded to (its PXC_VERSION). Same format as PXC_VERSION.'
    )
    string(
      name: 'PXC_LOWER_VERSION',
      defaultValue: 'PXC84',
      description: 'Upgrade run: the version upgraded from, always a released tarball. PXC80 | PXC84 | PXC57 = newest of that series, or a specific version: 8.4.10-10.1'
    )
    string(
      name: 'PXC_RHEL_GLIBC_VERSION',
      defaultValue: '2.34',
      description: 'glibc suffix of the PXC tarball used on the RedHat-family host (oracle-9)'
    )
    string(
      name: 'PXC_DEBIAN_GLIBC_VERSION',
      defaultValue: '2.35',
      description: 'glibc suffix of the PXC tarball used on the Debian-family host (debian-12)'
    )
    choice(
      name: 'PXC_TARBALL_SOURCE',
      choices: ['release', 'testing'],
      description: 'release: published tarballs. testing: pre-release tarballs (downloads/TESTING). PXC_LOWER_VERSION is always a released tarball.'
    )
    string(
      name: 'NUMBER_OF_WORKERS',
      defaultValue: '2',
      description: 'qa_framework.py --number-of-workers for the correctness/galera_sr/ssl and random_qa runs (upgrade, replication and loadtest always run sequentially)'
    )
    string(
      name: 'PARALLEL_INSTANCE_TYPE',
      defaultValue: 't3.2xlarge',
      description: 'EC2 instance type for the runs with workers; each worker runs its own 3-node cluster'
    )
    string(
      name: 'INSTANCE_TYPE',
      defaultValue: 't3.xlarge',
      description: 'EC2 instance type for the sequential upgrade, replication and loadtest runs'
    )
    string(
      name: 'DISABLED_TESTS',
      defaultValue: '',
      description: 'Comma-separated tests to skip in every run, e.g. consistency_check.py,correctness.chaosmonkey-test.py'
    )
    string(
      name: 'QA_CONFIG_OVERRIDES',
      defaultValue: '',
      description: 'Semicolon-separated config.ini overrides for every run, as section.key=value'
    )
    booleanParam(
      name: 'QA_DEBUG',
      defaultValue: false,
      description: 'Pass --debug to qa_framework.py'
    )
    string(
      name: 'PXC_QA_REPO',
      defaultValue: 'https://github.com/Percona-QA/pxc-qa',
      description: 'pxc-qa git repository'
    )
    string(
      name: 'PXC_QA_BRANCH',
      defaultValue: 'master',
      description: 'pxc-qa branch'
    )
    string(
      name: 'PSTRESS_BRANCH',
      defaultValue: 'master',
      description: 'Percona-QA/pstress branch (random_qa)'
    )
    string(
      name: 'PERCONA_QA_REPO',
      defaultValue: 'https://github.com/Percona-QA/percona-qa.git',
      description: 'percona-qa git repository; only its randgen/ directory is fetched'
    )
    string(
      name: 'PERCONA_QA_BRANCH',
      defaultValue: 'master',
      description: 'percona-qa branch for randgen'
    )
    string(
      name: 'TESTING_GIT_ACCOUNT',
      defaultValue: 'Percona-QA',
      description: 'GitHub account of the package-testing repo that holds molecule/pxc-qa-suites'
    )
    string(
      name: 'TESTING_BRANCH',
      defaultValue: 'master',
      description: 'Branch of the package-testing repository'
    )
    choice(
      name: 'TEST_OS',
      choices: ['all', 'debian-12', 'oracle-9'],
      description: 'Which test host(s) each run uses'
    )
    string(
      name: 'QA_TIMEOUT_HOURS',
      defaultValue: '10',
      description: 'Hard timeout for the qa_framework.py run on each host'
    )
  }
  options {
    disableConcurrentBuilds()
    disableResume()
    // pxc-qa-suites has its own 24h timeout; this only covers waiting in the queue on top.
    timeout(time: 26, unit: 'HOURS')
  }

  stages {
    stage('Set build name') {
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}-${p('PXC_VERSION')}-${params.TEST_OS}"
          currentBuild.description = "upgrade ${p('PXC_LOWER_VERSION')} -> ${p('UPGRADE_PXC_VERSION')} | pxc-qa@${p('PXC_QA_BRANCH')}"
        }
      }
    }

    stage('Run suites') {
      steps {
        script {
          parallel qaRuns().collectEntries { run ->
            [(run.name): {
              // A child that can't be started (e.g. bad parameter) must not stop the others.
              try {
                summaries[run.name] = runQaSuites(run)
              } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException err) {
                throw err
              } catch (err) {
                summaries[run.name] = "${run.name}: not run (${err})"
                unstable(summaries[run.name])
              }
            }]
          }
        }
      }
    }
  }

  post {
    always {
      script {
        if (summaries) {
          echo "pxc-qa-suites runs:\n${summaries.values().join('\n')}"
        }
      }
    }
  }
}
