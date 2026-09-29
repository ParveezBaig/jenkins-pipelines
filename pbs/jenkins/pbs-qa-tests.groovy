library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Each entry must match a scenario folder in package-testing molecule/pbs/molecule/.
// percona-binlog-server packages exist for el9 and Debian 13 (trixie), not for Debian 12.
def pbsTestOSes() {
  return ['debian-13', 'oracle-9']
}

// Regions used by the scenarios above (debian-13: us-west-1, oracle-9: us-west-2).
def pbsTestRegions() {
  return ['us-west-1', 'us-west-2']
}

// Safety net after molecule destroy: terminate anything still tagged with this build.
def deleteBuildInstances() {
    script {
        def awsCredentials = [
                sshUserPrivateKey(
                    credentialsId: 'MOLECULE_AWS_PRIVATE_KEY',
                    keyFileVariable: 'MOLECULE_AWS_PRIVATE_KEY',
                    passphraseVariable: '',
                    usernameVariable: ''
                ),
                aws(
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    credentialsId: 'c42456e5-c28d-4962-b32c-b75d161bff27',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                )
        ]

        withCredentials(awsCredentials) {
            def jobName = env.JOB_NAME.trim()
            pbsTestRegions().each { region ->
                def instanceIds = sh(
                    script: """
                    aws ec2 describe-instances --region ${region} \\
                    --filters "Name=tag:job-name,Values=${jobName}" "Name=tag:build-number,Values=${env.BUILD_NUMBER}" "Name=instance-state-name,Values=pending,running,stopping,stopped" \\
                    --query "Reservations[].Instances[].InstanceId" \\
                    --output text || true
                    """,
                    returnStdout: true
                ).trim()

                if (instanceIds) {
                    echo "Terminating leftover instances in ${region}: ${instanceIds}"
                    sh "aws ec2 terminate-instances --region ${region} --instance-ids ${instanceIds}"
                } else {
                    echo "No leftover instances in ${region}"
                }
            }
        }
    }
}

def loadEnvFile(envFilePath) {
    def envMap = []
    def envFileContent = readFile(file: envFilePath).trim().split('\n')
    envFileContent.each { line ->
        if (line && !line.startsWith('#')) {
            def parts = line.split('=')
            if (parts.length == 2) {
                envMap << "${parts[0].trim()}=${parts[1].trim()}"
            }
        }
    }
    return envMap
}


pipeline {
  agent {
    label 'deb12-x64-min'
  }
  environment {
    // String parameters are trimmed: a stray space breaks git refs, URLs and versions.
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pbs/";
    PS_VERSION = "${params.PS_VERSION?.trim() ?: ''}";
    PS_RHEL_GLIBC_VERSION = "${params.PS_RHEL_GLIBC_VERSION?.trim() ?: ''}";
    PS_DEBIAN_GLIBC_VERSION = "${params.PS_DEBIAN_GLIBC_VERSION?.trim() ?: ''}";
    PS_TARBALL_URL_RHEL = "${params.PS_TARBALL_URL_RHEL?.trim() ?: ''}";
    PS_TARBALL_URL_DEB = "${params.PS_TARBALL_URL_DEB?.trim() ?: ''}";
    PBS_VERSION = "${params.PBS_VERSION?.trim() ?: ''}";
    PBS_REPO_CHANNEL = "${params.PBS_REPO_CHANNEL}";
    PBS_BINARY_URL = "${params.PBS_BINARY_URL?.trim() ?: ''}";
    PBS_USE_DEBUG_BINARY = "${params.PBS_USE_DEBUG_BINARY}";
    PBS_QA_REPO = "${params.PBS_QA_REPO?.trim() ?: ''}";
    PBS_QA_BRANCH = "${params.PBS_QA_BRANCH?.trim() ?: ''}";
    PBS_TESTS = "${params.PBS_TESTS?.trim() ?: ''}";
    PBS_PARALLEL = "${params.PBS_PARALLEL?.trim() ?: ''}";
    PBS_ENCRYPTION = "${params.PBS_ENCRYPTION}";
    PBS_VERBOSE = "${params.PBS_VERBOSE}";
    PBS_CONFIG_OVERRIDES = "${params.PBS_CONFIG_OVERRIDES?.trim() ?: ''}";
    PBS_TIMEOUT_HOURS = "${params.PBS_TIMEOUT_HOURS?.trim() ?: ''}";
    TESTING_GIT_ACCOUNT = "${params.TESTING_GIT_ACCOUNT?.trim() ?: ''}";
    TESTING_BRANCH = "${params.TESTING_BRANCH?.trim() ?: ''}";
    INSTANCE_TYPE = "${params.INSTANCE_TYPE?.trim() ?: ''}";
  }
  parameters {
    string(
      name: 'PS_VERSION',
      defaultValue: '8.4.8-8',
      description: 'Percona Server full version of a released binary tarball, the binlog_server replication source (e.g. 8.4.8-8, 9.7.1-1)'
    )
    string(
      name: 'PS_RHEL_GLIBC_VERSION',
      defaultValue: '2.34',
      description: 'glibc suffix of the PS tarball used on the RedHat-family host (oracle-9)'
    )
    string(
      name: 'PS_DEBIAN_GLIBC_VERSION',
      defaultValue: '2.35',
      description: 'glibc suffix of the PS tarball used on the Debian-family host (debian-13)'
    )
    string(
      name: 'PS_TARBALL_URL_RHEL',
      defaultValue: '',
      description: 'Optional: full URL of a PS tarball for the RedHat host (e.g. an unreleased build). Overrides PS_VERSION there.'
    )
    string(
      name: 'PS_TARBALL_URL_DEB',
      defaultValue: '',
      description: 'Optional: full URL of a PS tarball for the Debian host. Overrides PS_VERSION there.'
    )
    string(
      name: 'PBS_VERSION',
      defaultValue: '0.4.1-1',
      description: 'percona-binlog-server package version to install (e.g. 0.4.1-1). Empty: latest in PBS_REPO_CHANNEL.'
    )
    choice(
      name: 'PBS_REPO_CHANNEL',
      choices: ['experimental', 'testing', 'release'],
      description: 'Channel of the Percona "tools" repository to install percona-binlog-server from (it is currently published in experimental only)'
    )
    string(
      name: 'PBS_BINARY_URL',
      defaultValue: '',
      description: 'Optional: URL of a binlog_server binary to test instead of the packaged one (e.g. an unreleased build). The package is still installed for its runtime libraries.'
    )
    booleanParam(
      name: 'PBS_USE_DEBUG_BINARY',
      defaultValue: false,
      description: 'Test the packaged /usr/bin/binlog_server-debug instead of /usr/bin/binlog_server'
    )
    string(
      name: 'PBS_TESTS',
      defaultValue: 'all',
      description: 'Comma-separated test_scripts/pbs/tests names (e.g. gtid_rewrite_test,pull_purge_resume_test), or "all" for every *_test.py'
    )
    string(
      name: 'PBS_PARALLEL',
      defaultValue: '0',
      description: 'pbs_test_runner.py --parallel (0 = sequential, max 4). Each test runs its own mysqld, so size INSTANCE_TYPE accordingly.'
    )
    booleanParam(
      name: 'PBS_ENCRYPTION',
      defaultValue: false,
      description: 'Pass --encryption to pbs_test_runner.py (storage encryption on for every test)'
    )
    booleanParam(
      name: 'PBS_VERBOSE',
      defaultValue: false,
      description: 'Pass --verbose to pbs_test_runner.py'
    )
    string(
      name: 'PBS_CONFIG_OVERRIDES',
      defaultValue: '',
      description: 'Semicolon-separated test_scripts/pbs/config.json overrides as key=value (nested: s3.bucket=...), e.g. sysbench_table_size=50000;sysbench_run_time=120;fetch_timeout=600. Do not put secrets here.'
    )
    string(
      name: 'PBS_QA_REPO',
      defaultValue: 'https://github.com/Percona-QA/server-qa.git',
      description: 'server-qa git repository holding the tests in test_scripts/pbs/. Use your fork until the tests are merged.'
    )
    string(
      name: 'PBS_QA_BRANCH',
      defaultValue: 'main',
      description: 'server-qa branch'
    )
    string(
      name: 'TESTING_GIT_ACCOUNT',
      defaultValue: 'Percona-QA',
      description: 'GitHub account of the package-testing repo that holds molecule/pbs (use your fork until it is merged)'
    )
    string(
      name: 'TESTING_BRANCH',
      defaultValue: 'master',
      description: 'Branch of the package-testing repository'
    )
    choice(
      name: 'TEST_OS',
      choices: ['all', 'debian-13', 'oracle-9'],
      description: 'Which test host(s) to run on'
    )
    string(
      name: 'INSTANCE_TYPE',
      defaultValue: 't3.xlarge',
      description: 'EC2 instance type for the test host(s)'
    )
    string(
      name: 'PBS_TIMEOUT_HOURS',
      defaultValue: '6',
      description: 'Hard timeout for the pbs_test_runner.py run on each host'
    )
  }
  options {
    withCredentials(moleculepxcJenkinsCreds())
    disableConcurrentBuilds()
    disableResume()
    timeout(time: 12, unit: 'HOURS')
  }

  stages {
    stage('Set build name') {
      steps {
        script {
          currentBuild.displayName = "${env.BUILD_NUMBER}-pbs${env.PBS_VERSION ?: '-latest'}-ps${env.PS_VERSION}-${params.TEST_OS}"
          currentBuild.description = "${env.PBS_TESTS ?: 'all'} | ${env.PBS_QA_REPO}@${env.PBS_QA_BRANCH}"
        }
      }
    }

    stage('Checkout') {
      steps {
        deleteDir()
        git poll: false, branch: env.TESTING_BRANCH, url: "https://github.com/${env.TESTING_GIT_ACCOUNT}/package-testing.git"
      }
    }

    stage('Prepare') {
      steps {
        script {
          installMoleculeBookwormMysql()
        }
      }
    }

    stage('Run test') {
      steps {
        script {
          sh """
              echo WORKSPACE_VAR=${WORKSPACE} >> .env.ENV_VARS
          """
          def envMap = loadEnvFile('.env.ENV_VARS')
          def skipOS = (params.TEST_OS == 'all') ? [] : pbsTestOSes().findAll { it != params.TEST_OS }
          withEnv(envMap) {
            moleculeParallelTestSkip(pbsTestOSes(), env.MOLECULE_DIR, skipOS)
          }
        }
      }
    }
  }

  post {
    always {
      script {
        archiveArtifacts artifacts: "pbs_logs_*.tar.gz", followSymlinks: false, allowEmptyArchive: true
        junit allowEmptyResults: true, testResults: "**/junit-pbs-*.xml"
        // The molecule venv only exists once Prepare has run (not after a checkout failure).
        // A destroy failure must not stop deleteBuildInstances() below from running.
        if (fileExists('virtenv/bin/activate')) {
          try {
            moleculeParallelPostDestroy(pbsTestOSes(), env.MOLECULE_DIR)
          } catch (err) {
            echo "molecule destroy failed (${err}); deleteBuildInstances() cleans up by tag"
          }
        } else {
          echo 'Molecule virtualenv not created; skipping molecule destroy'
        }
      }
      deleteBuildInstances()
    }
  }
}
