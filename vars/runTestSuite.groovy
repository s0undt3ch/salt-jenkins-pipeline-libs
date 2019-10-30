def call(Map options) {

    def env = options.get('env')
    def String distro_name = options.get('distro_name')
    def String distro_version = options.get('distro_version')
    def String python_version = options.get('python_version')
    def String salt_target_branch = options.get('salt_target_branch')
    def String golden_images_branch = options.get('golden_images_branch')
    def String nox_env_name = options.get('nox_env_name')
    def String nox_passthrough_opts = options.get('nox_passthrough_opts')
    def Integer testrun_timeout = options.get('testrun_timeout')
    def Boolean run_full = options.get('run_full', true)
    def Boolean use_spot_instances = options.get('use_spot_instances', false)
    def String rbenv_version = options.get('rbenv_version', '2.6.3')
    def String jenkins_slave_label = options.get('jenkins_slave_label', 'kitchen-slave')
    def String notify_slack_channel = options.get('notify_slack_channel', '#jenkins-prod-pr')
    def String kitchen_driver_file = options.get('kitchen_driver_file', '/var/jenkins/workspace/driver.yml')
    def String kitchen_verifier_file = options.get('kitchen_verifier_file', '/var/jenkins/workspace/nox-verifier.yml')
    def String kitchen_platforms_file = options.get('kitchen_platforms_file', '/var/jenkins/workspace/nox-platforms.yml')
    def String[] extra_codecov_flags = options.get('extra_codecov_flags', [])
    def Boolean retrying = options.get('retrying', false)
    def Boolean retrying = options.get('upload_test_coverage', true)
    def String vm_hostname = computeMachineHostname(
        env: env,
        distro_name: distro_name,
        distro_version: distro_version,
        python_version: python_version,
        nox_env_name: nox_env_name,
        extra_parts: extra_codecov_flags,
        retrying: retrying
    )

    def Boolean retry_build = false

    def Boolean macos_build = false
    if ( distro_name == 'macosx' ) {
        macos_build = true
    }

    // In case we're testing golden images
    def String ami_image_id = options.get('ami_image_id', '')

    // Define a global pipeline timeout. This is the test run timeout with one(1) additional
    // hour to allow for artifacts to be downloaded, if possible.
    def global_timeout = testrun_timeout + 1

    echo """
    Distro: ${distro_name}
    Distro Version: ${distro_version}
    Python Version: ${python_version}
    Salt Target Branch: ${salt_target_branch}
    Golden Images Branch: ${golden_images_branch}
    Nox Env Name: ${nox_env_name}
    Nox Passthrough Opts: ${nox_passthrough_opts}
    Test run timeout: ${testrun_timeout} Hours
    Global Timeout: ${global_timeout} Hours
    Full Testsuite Run: ${run_full}
    Use SPOT instances: ${use_spot_instances}
    RBEnv Version: ${rbenv_version}
    Jenkins Slave Label: ${jenkins_slave_label}
    Notify Slack Channel: ${notify_slack_channel}
    Kitchen Driver File: ${kitchen_driver_file}
    Kitchen Verifier File: ${kitchen_verifier_file}
    Kitchen Platforms File: ${kitchen_platforms_file}
    Computed Machine Hostname: ${vm_hostname}
    """

    def environ = [
        "SALT_KITCHEN_PLATFORMS=${kitchen_platforms_file}",
        "SALT_KITCHEN_VERIFIER=${kitchen_verifier_file}",
        "SALT_KITCHEN_DRIVER=${kitchen_driver_file}",
        "NOX_ENV_NAME=${nox_env_name.toLowerCase()}",
        'NOX_ENABLE_FROM_FILENAMES=true',
        "NOX_PASSTHROUGH_OPTS=${nox_passthrough_opts}",
        "SALT_TARGET_BRANCH=${salt_target_branch}",
        "GOLDEN_IMAGES_CI_BRANCH=${golden_images_branch}",
        "CODECOV_FLAGS=${distro_name}${distro_version},${python_version},${nox_env_name.toLowerCase().split('-').join(',')}",
        'PATH=~/.rbenv/shims:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin',
        "RBENV_VERSION=${rbenv_version}",
        "TEST_SUITE=${python_version}",
        "TEST_PLATFORM=${distro_name}-${distro_version}",
        "FORCE_FULL=${run_full}",
        "TEST_VM_HOSTNAME=${vm_hostname}"
    ]

    if ( ami_image_id != '' ) {
        environ << "AMI_IMAGE_ID=${ami_image_id}"
    }

    wrappedNode(jenkins_slave_label, global_timeout, notify_slack_channel) {
        withEnv(environ) {

            if ( macos_build ) {
                // Cleanup old VMs
                stage('VM Cleanup') {
                    sh '''
                    for i in `prlctl list -aij|jq -r '.[]|select((.Uptime|tonumber > 86400) and (.State == "running"))|.ID'`
                    do
                        prlctl stop $i --kill
                    done
                    # don't delete vm's that haven't started yet ((.State == "stopped") and (.Uptime == "0"))
                    for i in `prlctl list -aij|jq -r '.[]|select((.Uptime|tonumber > 0) and (.State != "running"))|.ID'`
                    do
                        prlctl delete $i
                    done
                    '''
                }
            }

            // Checkout the repo
            stage('Clone') {
                cleanWs notFailBuild: true
                if ( ami_image_id == '' ) {
                    checkout scm
                    sh 'git fetch --no-tags https://github.com/saltstack/salt.git +refs/heads/${SALT_TARGET_BRANCH}:refs/remotes/origin/${SALT_TARGET_BRANCH}'
                } else {
                    checkout([
                        $class: 'GitSCM',
                        branches: [
                            [name: "${salt_target_branch}"]
                        ],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [
                            [
                                $class: 'CloneOption',
                                noTags: false,
                                reference: '',
                                shallow: true
                            ]
                        ],
                        submoduleCfg: [],
                        userRemoteConfigs: [
                            [url: "https://github.com/saltstack/salt.git"]
                        ]
                    ])
                }
            }

            // Setup the kitchen required bundle
            stage('Setup') {
                if ( ami_image_id == '' ) {
                    if ( macos_build ) {
                        sh 'bundle install --with vagrant macos --without ec2 windows opennebula docker'
                    } else {
                        sh 'bundle install --with ec2 windows --without docker macos opennebula vagrant'
                    }
                } else {
                    sh 'bundle install --with ec2 windows --without docker macos opennebula vagrant'
                    // Make sure we don't get any promoted images
                    writeFile encoding: 'utf-8', file: '.kitchen.local.yml', text: """\
                    driver:
                      image_search:
                        description: 'CI-STAGING *'
                    verifier:
                      coverage: false
                    """.stripIndent()
                }
            }

            if ( ami_image_id =! '' ) {
                stage('Discover AMI') {
                    command_output = sh returnStdout: true, script:
                        '''
                        bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM | grep 'image_id:' | awk '{ print $2 }'
                        '''
                    image_id = command_output.trim()
                    if ( image_id != '' ) {
                        image_to_promote_available = true
                        addInfoBadge id: 'discovered-ami-badge', text: "Discovered AMI ${image_id} for this running instance"
                        createSummary(icon: "/images/48x48/attribute.png", text: "Discovered AMI: ${image_id}")
                    } else {
                        image_to_promote_available = false
                        addWarningBadge id: 'discovered-ami-badge', text: "No AMI discovered to promote"
                        createSummary(icon: "/images/48x48/warning.png", text: "No AMI discovered to promote")
                    }
                    command_output = sh returnStdout: true, script:
                        '''
                        grep 'region:' $SALT_KITCHEN_DRIVER | awk '{ print $2 }'
                        '''
                    ec2_region = command_output.trim()
                    println "Discovered EC2 Region: ${ec2_region}"
                }
            }

            stage('Create VM') {
                if ( macos_build ) {
                    sh '''
                    bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                    '''
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-create.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-create.log"
                    fi
                    """
                } else {
                    retry(3) {
                        if ( use_spot_instances ) {
                            sh '''
                            cp -f ~/workspace/spot.yml .kitchen.local.yml
                            t=$(shuf -i 30-120 -n 1); echo "Sleeping $t seconds"; sleep $t
                            bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM || (bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; rm .kitchen.local.yml; bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM); echo "ExitCode: $?";
                            rm -f .kitchen.local.yml
                            '''
                        } else {
                            sh '''
                            t=$(shuf -i 30-120 -n 1); echo "Sleeping $t seconds"; sleep $t
                            bundle exec kitchen create $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                            '''
                        }
                        sh """
                        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-create.log"
                        fi
                        if [ -s ".kitchen/logs/kitchen.log" ]; then
                            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-create.log"
                        fi
                        """
                    }
                    sh '''
                    bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM | grep 'image_id:'
                    bundle exec kitchen diagnose $TEST_SUITE-$TEST_PLATFORM | grep 'instance_type:' -A5
                    '''
                }
            }

            try {
                timeout(time: testrun_timeout, unit: 'HOURS') {
                    stage('Converge VM') {
                        if ( macos_build ) {
                            sh '''
                            ssh-agent /bin/bash -c 'ssh-add ~/.vagrant.d/insecure_private_key; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?"'
                            '''
                        } else {
                            sh '''
                            ssh-agent /bin/bash -c 'ssh-add ~/.ssh/kitchen.pem; bundle exec kitchen converge $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?"'
                            '''
                        }
                        sh """
                        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-converge.log"
                        fi
                        if [ -s ".kitchen/logs/kitchen.log" ]; then
                            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-converge.log"
                        fi
                        """
                    }
                    stage('Run Tests') {
                        withEnv(["DONT_DOWNLOAD_ARTEFACTS=1"]) {
                            sh 'bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";'
                        }
                    }
                }
            } finally {
                try {
                    sh """
                    if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                        mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-verify.log"
                    fi
                    if [ -s ".kitchen/logs/kitchen.log" ]; then
                        mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-verify.log"
                    fi
                    """

                    if ( retrying == false ) {
                        // Let's see if we should retry the build
                        retry_build_1 = checkRetriableConditions('.kitchen/logs/kitchen-verify.log')
                        retry_build_2 = checkRetriableConditions(".kitchen/logs/${python_version}-${distro_name}-${distro_version}-verify.log")

                        if ( retry_build_1 || retry_build_2 ) {
                            retry_build = true
                        }
                    }

                    stage('Download Artefacts') {
                        withEnv(["ONLY_DOWNLOAD_ARTEFACTS=1"]){
                            sh '''
                            bundle exec kitchen verify $TEST_SUITE-$TEST_PLATFORM || exit 0
                            '''
                        }
                        sh """
                        if [ -s ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ]; then
                            mv ".kitchen/logs/${python_version}-${distro_name}-${distro_version}.log" ".kitchen/logs/${python_version}-${distro_name}-${distro_version}-download.log"
                        fi
                        if [ -s ".kitchen/logs/kitchen.log" ]; then
                            mv ".kitchen/logs/kitchen.log" ".kitchen/logs/kitchen-download.log"
                        fi
                        """
                    }
                    archiveArtifacts(
                        artifacts: "artifacts/*,artifacts/**/*,.kitchen/logs/*-create.log,.kitchen/logs/*-converge.log,.kitchen/logs/*-verify.log,.kitchen/logs/*-download.log,artifacts/xml-unittests-output/*.xml",
                        allowEmptyArchive: true
                    )
                    junit 'artifacts/xml-unittests-output/*.xml'
                } finally {
                    stage('Cleanup') {
                        sh '''
                        bundle exec kitchen destroy $TEST_SUITE-$TEST_PLATFORM; echo "ExitCode: $?";
                        '''
                    }
                    if ( upload_test_coverage == true ) {
                        stage('Upload Coverage') {
                            if ( run_full ) {
                                def distro_strings = [
                                    distro_name,
                                    distro_version
                                ]
                                def report_strings = (
                                    [python_version] + nox_env_name.split('-') + extra_codecov_flags
                                ).flatten()
                                uploadCodeCoverage(
                                    report_path: 'artifacts/coverage/tests.xml',
                                    report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-tests",
                                    report_flags: ([distro_strings.join('')] + report_strings + ['tests']).flatten()
                                )
                                sleep(
                                    time: 5,
                                    unit: 'SECONDS'
                                )
                                uploadCodeCoverage(
                                    report_path: 'artifacts/coverage/salt.xml',
                                    report_name: "${distro_strings.join('-')}-${report_strings.join('-')}-salt",
                                    report_flags: ([distro_strings.join('')] + report_strings + ['salt']).flatten()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if ( retrying == false && retry_build == true) {
        throw new Exception('retry-build')
    }

}
// vim: ft=groovy ts=4 sts=4 et
