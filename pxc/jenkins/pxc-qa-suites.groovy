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

// Value of KEY="..." from package-testing's VERSIONS file (in the checked-out workspace),
// the release team's list of current versions. Empty string when the key is missing.
def versionFromVersionsFile(String key) {
    return sh(
        script: "grep -E '^${key}=' VERSIONS | head -1 | cut -d= -f2- | tr -d '\"'",
        returnStdout: true
    ).trim()
}

// "PXC84", "PXC97", "PXC57", ...: a series alias, resolved to the newest version whose
// tarball is available (see resolvePxcTarballs). Anything else is a specific version.
def isSeriesAlias(String value) {
    return value ==~ /PXC\d\d/
}

// "84" -> "8.4"
def seriesDotted(String series) {
    return "${series[0]}.${series.substring(1)}"
}

// Versions of the newest packages of a series in the percona-release repository
// (repo.percona.com/<repo>/yum/<component>/9/), newest first. A package being there doesn't
// mean the tarball is published, so these are only the candidates to check.
// regex: the package file name up to ".el9", with one group: the version to return.
def repoPackageVersions(String repo, List components, String regex, int count) {
    def listings = components.collect { "https://repo.percona.com/${repo}/yum/${it}/9/RPMS/x86_64/" }.join(' ')
    def out = sh(returnStdout: true, script: """
        for url in ${listings}; do curl -sf --max-time 60 "\$url" || true; done \\
          | grep -oE '${regex}' | sed -E 's/^${regex}\$/\\1/' | sort -uVr | head -${count}
    """).trim()
    return out ? out.split('\n').collect { it.trim() } : []
}

// Newest-first candidate tarball versions for a series alias:
//   8.x/9.x: <series>_VER from package-testing VERSIONS (8.4.11-11 -> tarball 8.4.11-11.1; it can be
//            ahead of what is built) plus the PXC server packages in the release (and, for
//            testing, the testing) repository, e.g. 8.4.11-11.1
//   5.7:     from the packages (5.7.43-31.65.1) and the Percona Server 5.7 release it is based on
//            (ps-57 5.7.43-47), as in the tarball name: 5.7.43-rel47-65.1
def pxcCandidateVersions(String alias, String source) {
    def series = alias.substring('PXC'.length())
    def dotted = seriesDotted(series).replace('.', '\\.')
    def repo = ['57': 'pxc-57', '80': 'pxc-80', '84': 'pxc-84-lts', '97': 'pxc-97-lts'][series]
    def components = source == 'testing' ? ['testing', 'release'] : ['release']
    def candidates = []
    if (series == '57') {
        def pxc = repo ? repoPackageVersions(repo, components,
                'Percona-XtraDB-Cluster-server-57-(5\\.7\\.[0-9]+-[0-9]+\\.[0-9]+\\.[0-9]+)\\.el9', 4) : []
        for (pkg in pxc) {
            // 5.7.43-31.65.1 -> upstream 5.7.43, wsrep 65, build 1
            def upstream = pkg.tokenize('-')[0]
            def parts = pkg.tokenize('-')[1].tokenize('.')
            def rels = repoPackageVersions('ps-57', components,
                    "Percona-Server-server-57-${upstream.replace('.', '\\.')}-([0-9]+)\\.[0-9]+\\.el9", 3)
            for (rel in rels) {
                candidates << "${upstream}-rel${rel}-${parts[1]}.${parts[2]}".toString()
            }
        }
    } else {
        def fromVersions = versionFromVersionsFile("${alias}_VER")
        if (fromVersions) {
            candidates << (fromVersions.substring(fromVersions.indexOf('-')).contains('.') ? fromVersions : "${fromVersions}.1".toString())
        }
        if (repo) {
            candidates += repoPackageVersions(repo, components,
                    "percona-xtradb-cluster-server-(${dotted}\\.[0-9]+-[0-9]+\\.[0-9]+)\\.el9", 5)
        }
        candidates = sortVersionsDesc(candidates)
    }
    return candidates
}

def sortVersionsDesc(List versions) {
    if (!versions) {
        return []
    }
    return sh(returnStdout: true, script: "printf '%s\\n' ${versions.collect { "'${it}'" }.join(' ')} | sort -uVr").trim().split('\n') as List
}

// PXC binary tarball URL.
//   release: the published tarball (same layout as molecule/pxc-qa-suites/tasks/main.yml)
//   testing: the pre-release tarball the build pipeline uploads to downloads/TESTING
//            (vars/uploadTarballToDownloadsTesting.groovy: TESTING/pxc-<major.minor.patch>/)
// 5.7 tarballs are built only for glibc 2.17 and named differently
// (Percona-XtraDB-Cluster-5.7.43-rel47-65.1.Linux.x86_64.glibc2.17.tar.gz).
def pxcTarballUrl(String version, String glibc, String source) {
    def upstream = version.tokenize('-')[0]
    def series = upstream.tokenize('.')[0..1].join('')
    def file = series == '57' ?
        "Percona-XtraDB-Cluster-${version}.Linux.x86_64.glibc2.17.tar.gz" :
        "Percona-XtraDB-Cluster_${version}_Linux.x86_64.glibc${glibc}.tar.gz"
    if (source == 'testing') {
        return "https://downloads.percona.com/downloads/TESTING/pxc-${upstream}/${file}"
    }
    return "https://downloads.percona.com/downloads/Percona-XtraDB-Cluster-${series}/Percona-XtraDB-Cluster-${upstream}/binary/tarball/${file}"
}

def urlExists(String url) {
    return sh(script: "curl -sfI -o /dev/null --max-time 60 '${url}'", returnStatus: true) == 0
}

// Picks the tarball of every host for a PXC_VERSION-style value:
//   specific version: that version; it must exist.
//   series alias:     the newest candidate (pxcCandidateVersions) available for every host.
// source testing: a series alias looks in downloads/TESTING first and takes the released
// tarball when there is none, so it gets the newest of the two; a specific version must be
// in downloads/TESTING. An explicit URL in
// host.override is used as given. Returns [version: ..., urls: [<family>: <url>]].
def resolvePxcTarballs(String value, Map hosts, String source) {
    def alias = isSeriesAlias(value)
    def candidates = alias ? pxcCandidateVersions(value, source) : [value]
    // A series alias on testing also takes released tarballs (the newest of both); a specific
    // version on testing must be a TESTING tarball.
    def sources = source != 'testing' ? ['release'] : (alias ? ['testing', 'release'] : ['testing'])
    echo "${value} (${source}): checking ${candidates}"
    for (version in candidates) {
        def urls = [:]
        def missing = []
        hosts.each { os, cfg ->
            if (cfg.override) {
                urls[cfg.family] = cfg.override
                return
            }
            def found = ''
            for (src in sources) {
                def url = pxcTarballUrl(version, cfg.glibc, src)
                if (urlExists(url)) {
                    found = url
                    break
                }
            }
            if (found) {
                urls[cfg.family] = found
            } else {
                missing << os
            }
        }
        if (!missing) {
            if (version != candidates[0]) {
                echo "${value}: newer ${candidates.takeWhile { it != version }} not available as tarball(s), using ${version}"
            }
            urls.each { family, url -> echo "${family}: ${url}" }
            return [version: version, urls: urls]
        }
        echo "${version}: no tarball for ${missing}"
    }
    error "No PXC tarball available for ${value} (${source}) on ${hosts.keySet()}; checked ${candidates}. " +
          (alias ? 'Set a specific PXC_VERSION or PXC_TARBALL_URL_*.' : 'Check the version, or set PXC_TARBALL_URL_*.')
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
    PXC_TARBALL_SOURCE = "${params.PXC_TARBALL_SOURCE}";
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
      defaultValue: 'PXC84',
      description: 'PXC84 | PXC97 | PXC80 | PXC57 = newest available tarball of that series (from PXC_TARBALL_SOURCE), or a specific version, used exactly: 8.4.10-10.1, 8.0.44-35.1, 5.7.43-rel47-65.1'
    )
    string(
      name: 'PXC_RHEL_GLIBC_VERSION',
      defaultValue: '2.34',
      description: 'glibc suffix of the PXC tarball used on the RedHat-family host (oracle-9). Not used for 5.7 (glibc2.17 tarballs only).'
    )
    string(
      name: 'PXC_DEBIAN_GLIBC_VERSION',
      defaultValue: '2.35',
      description: 'glibc suffix of the PXC tarball used on the Debian-family host (debian-12). Not used for 5.7 (glibc2.17 tarballs only).'
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
    choice(
      name: 'PXC_TARBALL_SOURCE',
      choices: ['release', 'testing'],
      description: 'release: published tarballs (downloads.percona.com). testing: pre-release tarballs (downloads.percona.com/downloads/TESTING/pxc-<x.y.z>/). A series alias also takes published tarballs, so it gets the newest of both; a specific version must be a TESTING tarball. PXC_LOWER_VERSION is always a released tarball.'
    )
    string(
      name: 'PXC_LOWER_VERSION',
      defaultValue: '',
      description: 'Upgrade suite only (required there, ignored otherwise): the version to upgrade from, always a released tarball. PXC80 | PXC84 | PXC57 = newest of that series, or a specific version, used exactly: 8.0.44-35.1, 5.7.43-rel47-65.1. PXC_VERSION is the version upgraded to.'
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

    // PXC_VERSION / PXC_LOWER_VERSION -> tarball URL per host (resolvePxcTarballs), checked
    // before any EC2 host is created. The URLs are passed to the role as PXC_TARBALL_URL_* /
    // PXC_LOWER_TARBALL_URL_*, so this is the one place that decides them. The lower
    // (upgrade) version is always a released tarball.
    stage('Resolve versions') {
      steps {
        script {
          def hosts = [
            'oracle-9' : [glibc: env.PXC_RHEL_GLIBC_VERSION, override: env.PXC_TARBALL_URL_RHEL, family: 'RHEL'],
            'debian-12': [glibc: env.PXC_DEBIAN_GLIBC_VERSION, override: env.PXC_TARBALL_URL_DEB, family: 'DEB'],
          ].findAll { os, cfg -> params.TEST_OS == 'all' || params.TEST_OS == os }
          def main = resolvePxcTarballs(env.PXC_VERSION, hosts, env.PXC_TARBALL_SOURCE)
          env.PXC_VERSION_RESOLVED = main.version
          env.PXC_TARBALL_URL_RESOLVED_RHEL = main.urls['RHEL'] ?: ''
          env.PXC_TARBALL_URL_RESOLVED_DEB = main.urls['DEB'] ?: ''
          env.PXC_LOWER_VERSION_RESOLVED = ''
          env.PXC_LOWER_TARBALL_URL_RESOLVED_RHEL = ''
          env.PXC_LOWER_TARBALL_URL_RESOLVED_DEB = ''
          if (env.PXC_LOWER_VERSION) {
            def lowerHosts = hosts.collectEntries { os, cfg -> [(os): cfg + [override: '']] }
            def lower = resolvePxcTarballs(env.PXC_LOWER_VERSION, lowerHosts, 'release')
            env.PXC_LOWER_VERSION_RESOLVED = lower.version
            env.PXC_LOWER_TARBALL_URL_RESOLVED_RHEL = lower.urls['RHEL'] ?: ''
            env.PXC_LOWER_TARBALL_URL_RESOLVED_DEB = lower.urls['DEB'] ?: ''
          }
          def fromTesting = main.urls.values().any { it.contains('/downloads/TESTING/') } ? '-testing' : ''
          currentBuild.displayName = "${env.BUILD_NUMBER}-${env.PXC_VERSION_RESOLVED}${fromTesting}-${params.TEST_OS}"
        }
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
          // environment {} values can't be reassigned via env.X, so pass the resolved versions here
          def resolved = ["PXC_VERSION=${env.PXC_VERSION_RESOLVED}",
                          "PXC_LOWER_VERSION=${env.PXC_LOWER_VERSION_RESOLVED}",
                          "PXC_TARBALL_URL_RHEL=${env.PXC_TARBALL_URL_RESOLVED_RHEL}",
                          "PXC_TARBALL_URL_DEB=${env.PXC_TARBALL_URL_RESOLVED_DEB}",
                          "PXC_LOWER_TARBALL_URL_RHEL=${env.PXC_LOWER_TARBALL_URL_RESOLVED_RHEL}",
                          "PXC_LOWER_TARBALL_URL_DEB=${env.PXC_LOWER_TARBALL_URL_RESOLVED_DEB}"]
          withEnv(envMap + resolved) {
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
