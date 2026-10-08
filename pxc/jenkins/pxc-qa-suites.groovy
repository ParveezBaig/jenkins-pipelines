library changelog: false, identifier: "lib@master", retriever: modernSCM([
    $class: 'GitSCMSource',
    remote: 'https://github.com/Percona-Lab/jenkins-pipelines.git'
])

// Each entry must match a scenario folder in package-testing molecule/pxc-qa-suites/molecule/.
def qaTestOSes() {
  return ['debian-12', 'oracle-9']
}

// Regions used by the scenarios above (debian-12: us-west-1, oracle-9: us-west-2).
def qaTestRegions() {
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
            qaTestRegions().each { region ->
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
    // String parameters are trimmed: a stray space (e.g. " pxc-qa-suites") breaks git refs and URLs.
    PATH = '/usr/local/bin:/usr/bin:/usr/local/sbin:/usr/sbin:/home/ec2-user/.local/bin';
    MOLECULE_DIR = "molecule/pxc-qa-suites/";
    PXC_VERSION = "${params.PXC_VERSION?.trim() ?: ''}";
    PXC_RHEL_GLIBC_VERSION = "${params.PXC_RHEL_GLIBC_VERSION?.trim() ?: ''}";
    PXC_DEBIAN_GLIBC_VERSION = "${params.PXC_DEBIAN_GLIBC_VERSION?.trim() ?: ''}";
    PXC_TARBALL_URL_RHEL = "${params.PXC_TARBALL_URL_RHEL?.trim() ?: ''}";
    PXC_TARBALL_URL_DEB = "${params.PXC_TARBALL_URL_DEB?.trim() ?: ''}";
    PXC_LOWER_VERSION = "${params.PXC_LOWER_VERSION?.trim() ?: ''}";
    PXC_QA_REPO = "${params.PXC_QA_REPO?.trim() ?: ''}";
    PXC_QA_BRANCH = "${params.PXC_QA_BRANCH?.trim() ?: ''}";
    PSTRESS_BRANCH = "${params.PSTRESS_BRANCH?.trim() ?: ''}";
    PERCONA_QA_REPO = "${params.PERCONA_QA_REPO?.trim() ?: ''}";
    PERCONA_QA_BRANCH = "${params.PERCONA_QA_BRANCH?.trim() ?: ''}";
    TESTING_GIT_ACCOUNT = "${params.TESTING_GIT_ACCOUNT?.trim() ?: ''}";
    TESTING_BRANCH = "${params.TESTING_BRANCH?.trim() ?: ''}";
    QA_SUITES = "${params.QA_SUITES?.trim() ?: ''}";
    QA_TESTS = "${params.QA_TESTS?.trim() ?: ''}";
    DISABLED_TESTS = "${params.DISABLED_TESTS?.trim() ?: ''}";
    ENCRYPTION_RUN = "${params.ENCRYPTION_RUN}";
    QA_DEBUG = "${params.QA_DEBUG}";
    NUMBER_OF_WORKERS = "${params.NUMBER_OF_WORKERS?.trim() ?: ''}";
    BUILD_PSTRESS = "${params.BUILD_PSTRESS}";
    INSTALL_PXB = "${params.INSTALL_PXB}";
    QA_CONFIG_OVERRIDES = "${params.QA_CONFIG_OVERRIDES?.trim() ?: ''}";
    QA_TIMEOUT_HOURS = "${params.QA_TIMEOUT_HOURS?.trim() ?: ''}";
    INSTANCE_TYPE = "${params.INSTANCE_TYPE?.trim() ?: ''}";
  }
  parameters {
    string(
      name: 'PXC_VERSION',
      defaultValue: '8.4.7-7.1',
      description: 'PXC full version of a released tarball, e.g. 8.4.7-7.1 or 8.0.44-35.1'
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
    string(
      name: 'PXC_TARBALL_URL_RHEL',
      defaultValue: '',
      description: 'Optional: full URL of a PXC tarball for the RedHat host (e.g. an unreleased build). Overrides PXC_VERSION there.'
    )
    string(
      name: 'PXC_TARBALL_URL_DEB',
      defaultValue: '',
      description: 'Optional: full URL of a PXC tarball for the Debian host. Overrides PXC_VERSION there.'
    )
    string(
      name: 'PXC_LOWER_VERSION',
      defaultValue: '',
      description: 'Required for the upgrade suite, ignored otherwise: older released PXC version to upgrade from (e.g. 8.0.44-35.1). PXC_VERSION is the version upgraded to.'
    )
    string(
      name: 'QA_SUITES',
      defaultValue: 'correctness',
      description: 'Comma-separated pxc-qa suites: sysbench_run, loadtest, replication, correctness, ssl, upgrade, random_qa, galera_sr. Empty with empty QA_TESTS: framework defaults (all except loadtest, random_qa, upgrade).'
    )
    string(
      name: 'QA_TESTS',
      defaultValue: '',
      description: 'Comma-separated tests, e.g. replication.py,ssl.ssl_qa.py (suite.test.py to be explicit). Overrides the whole-suite run.'
    )
    string(
      name: 'DISABLED_TESTS',
      defaultValue: '',
      description: 'Comma-separated tests to skip, appended to the pxc-qa checkout disabled.list (created if missing; its own entries stay), e.g. consistency_check.py,correctness.chaosmonkey-test.py'
    )
    booleanParam(
      name: 'ENCRYPTION_RUN',
      defaultValue: false,
      description: 'Pass --encryption-run to qa_framework.py'
    )
    booleanParam(
      name: 'QA_DEBUG',
      defaultValue: false,
      description: 'Pass --debug to qa_framework.py'
    )
    string(
      name: 'NUMBER_OF_WORKERS',
      defaultValue: '0',
      description: 'qa_framework.py --number-of-workers (0 = sequential). Each worker runs its own 3-node cluster, so size INSTANCE_TYPE accordingly.'
    )
    booleanParam(
      name: 'BUILD_PSTRESS',
      defaultValue: false,
      description: 'Build pstress-pxc even if random_qa is not selected (it is built automatically for random_qa)'
    )
    booleanParam(
      name: 'INSTALL_PXB',
      defaultValue: false,
      description: 'Install the Percona XtraBackup package even if replication/backup_replication.py is not selected (it is installed automatically when it can run)'
    )
    string(
      name: 'QA_CONFIG_OVERRIDES',
      defaultValue: '',
      description: 'Semicolon-separated config.ini overrides as section.key=value, e.g. sysbench.sysbench_run_time=60;sysbench.sysbench_oltp_test_table_size=100000'
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
      description: 'Percona-QA/pstress branch (used only when pstress is built)'
    )
    string(
      name: 'PERCONA_QA_REPO',
      defaultValue: 'https://github.com/Percona-QA/percona-qa.git',
      description: 'percona-qa git repository; only its randgen/ directory is fetched (config.ini randgen_dir)'
    )
    string(
      name: 'PERCONA_QA_BRANCH',
      defaultValue: 'master',
      description: 'percona-qa branch for randgen'
    )
    string(
      name: 'TESTING_GIT_ACCOUNT',
      defaultValue: 'Percona-QA',
      description: 'GitHub account of the package-testing repo that holds molecule/pxc-qa-suites (use your fork until it is merged)'
    )
    string(
      name: 'TESTING_BRANCH',
      defaultValue: 'master',
      description: 'Branch of the package-testing repository'
    )
    choice(
      name: 'TEST_OS',
      choices: ['all', 'debian-12', 'oracle-9'],
      description: 'Which test host(s) to run on'
    )
    string(
      name: 'INSTANCE_TYPE',
      defaultValue: 't3.xlarge',
      description: 'EC2 instance type for the test host(s)'
    )
    string(
      name: 'QA_TIMEOUT_HOURS',
      defaultValue: '10',
      description: 'Hard timeout for the qa_framework.py run on each host'
    )
  }
  options {
    withCredentials(moleculepxcJenkinsCreds())
    disableConcurrentBuilds()
    disableResume()
    timeout(time: 24, unit: 'HOURS')
  }

  stages {
    stage('Set build name') {
      steps {
        script {
          def what = params.QA_TESTS?.trim() ? params.QA_TESTS.trim() : (params.QA_SUITES?.trim() ?: 'default-suites')
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXC_VERSION}-${params.TEST_OS}"
          currentBuild.description = "${what} | pxc-qa@${env.PXC_QA_BRANCH}"
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
          def skipOS = (params.TEST_OS == 'all') ? [] : qaTestOSes().findAll { it != params.TEST_OS }
          withEnv(envMap) {
            moleculeParallelTestSkip(qaTestOSes(), env.MOLECULE_DIR, skipOS)
          }
        }
      }
    }
  }

  post {
    always {
      script {
        archiveArtifacts artifacts: "pxc_qa_logs_*.tar.gz", followSymlinks: false, allowEmptyArchive: true
        junit allowEmptyResults: true, testResults: "**/junit-pxc-qa-*.xml"
        // The molecule venv only exists once Prepare has run (not after a checkout failure).
        // A destroy failure must not stop deleteBuildInstances() below from running.
        if (fileExists('virtenv/bin/activate')) {
          try {
            moleculeParallelPostDestroy(qaTestOSes(), env.MOLECULE_DIR)
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
