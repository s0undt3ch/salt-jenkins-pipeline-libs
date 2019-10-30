def call(Map options) {

    def env = options.get('env')
    def String distro_name = options.get('distro_name')
    def String distro_version = options.get('distro_version')
    def String salt_target_branch = options.get('salt_target_branch')
    def String golden_images_branch = options.get('golden_images_branch')
    def Integer concurrent_builds = options.get('concurrent_builds', 1)
    def Boolean supports_py2 = options.get('supports_py2', true)
    def Boolean supports_py3 = options.get('supports_py3', true)
    def String ec2_region = options.get('ec2_region', 'us-west-2')
    def String os_imager_tag
    def String salt_jenkins_head

    stage('Fake Checkout') {
      node(jenkins_slave_label) {
        checkout scm
        cleanWs notFailBUild: true
      }
    }

    stage('Build Image') {
        node(jenkins_slave_label) {
            timeout(time: 1, unit: 'HOURS') {
                timestamps {
                    ansiColor('xterm') {
                        cleanWs notFailBuild: true
                        os_imager_tag = sh (
                            script: '''
                            git ls-remote https://github.com/saltstack/os-imager.git|awk '{print $2}'|grep refs/tags/aws-ci-v|sort -V|tail -n 1
                            ''',
                            returnStdout: true
                            ).trim()
                        checkout([$class: 'GitSCM',
                            branches: [[name: "$os_imager_tag"]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [],
                            submoduleCfg: [],
                            userRemoteConfigs: [[url: 'https://github.com/saltstack/os-imager.git']]
                            ])
                        println "Using EC2 Region: ${ec2_region}"
                        salt_jenkins_head = sh (
                            script: """
                            git ls-remote https://github.com/saltstack/salt-jenkins.git|grep refs/pull/${env.CHANGE_ID}/head\$ || true
                            """,
                            returnStdout: true
                            ).trim()
                            if ( salt_jenkins_head != '' ) {
                                addInfoBadge id: 'discovered-salt-jenkins-head-badge', text: "salt-jenkins current head: ${salt_jenkins_head}"
                                createSummary(icon: "/images/48x48/attribute.png", text: "salt-jenkins current head: ${salt_jenkins_head}")
                            }
                        withAWS(credentials: 'os-imager-aws-creds', region: "${ec2_region}") {
                            sh """
                            pyenv install 3.6.8 || echo "We already have this python."
                            pyenv local 3.6.8
                            pip freeze | grep -s invoke || pip install -r requirements/py3.6/base.txt
                            inv build-aws --staging --distro=${distro_name} --distro-version=${distro_version} --salt-branch=${golden_image_branch} --salt-pr=${env.CHANGE_ID}
                            """
                        }
                        ami_image_id = sh (
                            script: """
                            cat ${golden_image_branch}-manifest.json|jq -r ".builds[].artifact_id"|cut -f2 -d:
                            """,
                            returnStdout: true
                            ).trim()
                        ami_name_filter = sh (
                            script: """
                            cat ${golden_image_branch}-manifest.json|jq -r ".builds[].custom_data.ami_name"
                            """,
                            returnStdout: true
                            ).trim()
                        cleanWs notFailBuild: true
                    }
                }
            }
        }
    }

    def buildNumber = env.BUILD_NUMBER as int
    if (buildNumber > concurrent_builds) {
        // This will cancel the previous build which also defined a matching milestone
        milestone(buildNumber - concurrent_builds)
    }
    // Define a milestone for this build so that, if another build starts, this one will be aborted
    milestone(buildNumber)

    options['ami_image_id'] = ami_image_id
    options['upload_test_coverage'] = false

    try {
        if ( supports_py2 == true && supports_py3 == true ) {
            parallel(
                Py2: {
                    def py2_options = options.clone()
                    py2_options['py_version'] = 'py2'
                    runTests(py2_options)
                },
                Py3: {
                    def py3_options = options.clone()
                    py3_options['py_version'] = 'py3'
                    runTests(py3_options)
                },
                failFast: true
            )
        } else if ( supports_py2 == true ) {
            options['py_version'] = 'py2'
            runTests(options)
        } else if ( supports_py3 == true ) {
            options['py_version'] = 'py3'
            runTests(options)
        }
    } finally {
        stage('Cleanup Old AMIs') {
            if (ami_name_filter) {
                node(jenkins_slave_label) {
                    timeout(time: 10, unit: 'MINUTES') {
                        cleanWs notFailBuild: true
                        checkout([$class: 'GitSCM',
                                  branches: [[name: "$os_imager_tag"]],
                                  doGenerateSubmoduleConfigurations: false,
                                  extensions: [],
                                  submoduleCfg: [],
                                  userRemoteConfigs: [
                                      [url: 'https://github.com/saltstack/os-imager.git']
                                  ]])
                        withAWS(credentials: 'os-imager-aws-creds', region: "${ec2_region}") {
                            sh """
                            pyenv install 3.6.8 || echo "We already have this python."
                            pyenv local 3.6.8
                            pip freeze | grep -s invoke || pip install -r requirements/py3.6/base.txt
                            inv cleanup-aws --staging --name-filter='${ami_name_filter}' --region=${ec2_region} --assume-yes --num-to-keep=1
                            """
                        }
                        cleanWs notFailBuild: true
                    }
                }
            }
        }
    }
}
// vim: ft=groovy ts=4 sts=4 et
