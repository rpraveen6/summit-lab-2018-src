#!/usr/bin/env groovy

//  list of apps
// devlop / master branches for each pipeline
def pipelineNames = ["dev", "test", "uat"]
def appName = "summit-labs-fe"

//  Globals for across all the jobs
def gitBaseUrlFE = "https://github.com/rht-labs-events/summit-lab-2018-src.git"
def pipelineNamespace = "ci-cd"
newLine = System.getProperty("line.separator")

def pipelineGeneratorVersion = "${JOB_NAME}.${BUILD_ID}"

def jenkinsGitCreds = "jenkins-id-as-seen-in-j-man"

//  Common functions repeated across the jobs
def buildWrappers(context) {
    context.ansiColorBuildWrapper {
        colorMapName('xterm')
    }
}


def rotateLogs(context) {
    context.logRotator {
        daysToKeep(100)
        artifactNumToKeep(2)
    }
}


pipelineNames.each {
    def pipelineName = it
    def buildImageName = it + "-" + appName + "-build"
    def bakeImageName = it + "-" + appName + "-bake"
    def deployImageName = it + "-" + appName + "-deploy"
    def projectNamespace = "labs-" + it
    def jobDescription = "THIS JOB WAS GENERATED BY THE JENKINS SEED JOB - ${pipelineGeneratorVersion}.  \n"  + it + " fe build job for the app."

    job(buildImageName) {
        description(jobDescription)
        label('npm-jenkins-slave')

        rotateLogs(delegate)

        wrappers {
            buildWrappers(delegate)

            preScmSteps {
                // Fails the build when one of the steps fails.
                failOnError(true)
                // Adds build steps to be run before SCM checkout.
                steps {
                    //  TODO - add git creds here
                    shell('git config --global http.sslVerify false' + newLine +
                            'git config --global user.name jenkins' + newLine +
                            'git config --global user.email jenkins@cc.net')
                }
            }
        }
        scm {
            git {
                remote {
                    name('origin')
                    url(gitBaseUrlFE)
                }
                if (pipelineName.contains('test')){
                    branch('master')
                }
                else {
                    branch('master')
                }
            }
        }
        //if (pipelineName.contains('dev')){
            triggers {
                cron('*/5 * * * *')
            }
        //}
        steps {
            steps {
                shell('#!/bin/bash' + newLine +
                        'NAME=' + appName  + newLine +
                        'set -o xtrace' + newLine +
                        'cd ./lab-app/ ' + newLine +
                        'scl enable rh-nodejs6 \'npm install\'' + newLine +
                        'scl enable rh-nodejs6 \'npm run test:lint\'' + newLine +
                        'scl enable rh-nodejs6 \'npm run test-ci\'' + newLine +
                        'scl enable rh-nodejs6 \'npm run build\'' + newLine +
                        'mkdir package-contents' + newLine +
                        'mv Dockerfile build package-contents' + newLine +
                        'zip -r cc-fe.zip package-contents')
            }
        }
        publishers {
            // nexus upload
            postBuildScripts {
                steps {
                    shell('cd ./lab-app/ && \\' + newLine +
                                'curl -v -F r=releases \\' + newLine +
                                '-F hasPom=false \\' + newLine +
                                '-F e=zip \\' + newLine +
                                '-F g=com.example.react \\' + newLine +
                                '-F a=cc-fe \\' + newLine +
                                '-F v=0.0.1-${JOB_NAME}.${BUILD_NUMBER} \\' + newLine +
                                '-F p=zip \\' + newLine +
                                '-F file=@cc-fe.zip \\' + newLine +
                                '-u admin:admin123 http://nexus-v2.ci-cd.svc.cluster.local:8081/nexus/service/local/artifact/maven/content')
                }
            }

            archiveArtifacts('**')

            coverageReport(delegate)


            downstreamParameterized {
                trigger(bakeImageName) {
                    condition('UNSTABLE_OR_BETTER')
                    parameters {
                        predefinedBuildParameters{
                            properties("BUILD_TAG=\${JOB_NAME}.\${BUILD_NUMBER}")
                            textParamValueOnNewLine(true)
                        }
                    }
                }
            }

            notifySlack(delegate)
        }
    }


    job(bakeImageName) {
        description(jobDescription)
        parameters{
            string{
                name("BUILD_TAG")
                defaultValue("my-app-build.1234")
                description("The BUILD_TAG is the \${JOB_NAME}.\${BUILD_NUMBER} of the successful build to be promoted.")
            }
        }
        rotateLogs(delegate)

        wrappers {
            buildWrappers(delegate)
        }
        steps {
            steps {
                shell('#!/bin/bash' + newLine +
                        'set -o xtrace' + newLine +
                        '# WIPE PREVIOUS BINARY' + newLine +
                        'rm -rf *.zip package-contents' + newLine +
                        '# GET BINARY - DIRTY GET BINARY HACK' + newLine +
                        'curl -v -f http://admin:admin123@nexus-v2.ci-cd.svc.cluster.local:8081/nexus/service/local/repositories/releases/content/com/example/react/cc-fe/0.0.1-${BUILD_TAG}/cc-fe-0.0.1-${BUILD_TAG}.zip -o cc-fe.zip' + newLine +
                        'unzip cc-fe.zip' + newLine +
                        'oc project ci-cd'  + newLine +
                        '# DO OC BUILD STUFF WITH BINARY NOW' + newLine +
                        'NAME=' + appName  + newLine +
                        'oc patch bc ${NAME} -p "spec:' + newLine +
                        '   nodeSelector:' + newLine +
                        '   output:' + newLine +
                        '     to:' + newLine +
                        '       kind: ImageStreamTag' + newLine +
                        '       name: \'${NAME}:${JOB_NAME}.${BUILD_NUMBER}\'"' + newLine +
                        'oc start-build ${NAME} --from-dir=package-contents/ --follow')
            }
        }
        publishers {
            downstreamParameterized {
                trigger(deployImageName) {
                    condition('SUCCESS')
                    parameters {
                        predefinedBuildParameters{
                            properties("BUILD_TAG=\${JOB_NAME}.\${BUILD_NUMBER}")
                            textParamValueOnNewLine(true)
                        }
                    }
                }
            }
            notifySlack(delegate)
        }
    }

    job(deployImageName) {
        description(jobDescription)
        parameters {
            string{
                name("BUILD_TAG")
                defaultValue("my-app-build.1234")
                description("The BUILD_TAG is the \${JOB_NAME}.\${BUILD_NUMBER} of the successful build to be promoted.")
            }
        }
        rotateLogs(delegate)

        wrappers {
            buildWrappers(delegate)

        }
        steps {
            steps {
                shell('#!/bin/bash' + newLine +
                        'set -o xtrace' + newLine +
                        'PIPELINES_NAMESPACE=' + pipelineNamespace  + newLine +
                        'NAMESPACE=' + projectNamespace  + newLine +
                        'NAME=' + appName  + newLine +
                        'oc tag ${PIPELINES_NAMESPACE}/${NAME}:${BUILD_TAG} ${NAMESPACE}/${NAME}:${BUILD_TAG}' + newLine +
                        'oc project ${NAMESPACE}' + newLine +
                        'oc patch dc ${NAME} -p "spec:' + newLine +
                        '  template:' + newLine +
                        '    spec:' + newLine +
                        '      containers:' + newLine +
                        '        - name: ${NAME}' + newLine +
                        '          image: \'docker-registry.default.svc:5000/${NAMESPACE}/${NAME}:${BUILD_TAG}\'' + newLine +
                        '          env:' + newLine +
                        '            - name: NODE_ENV' + newLine +
                        '              value: \'production\'"' + newLine +
                        'oc rollout latest dc/${NAME}')
            }
            openShiftDeploymentVerifier {
                apiURL('')
                depCfg(appName)
                namespace(projectNamespace)
                // This optional field's value represents the number expected running pods for the deployment for the DeploymentConfig specified.
                replicaCount('1')
                authToken('')
                verbose('yes')
                // This flag is the toggle for turning on or off the verification that the specified replica count for the deployment has been reached.
                verifyReplicaCount('yes')
                waitTime('')
                waitUnit('sec')
            }
        }
        publishers {
            notifySlack(delegate)
        }
    }
    buildPipelineView(pipelineName  + '-' + appName + "-pipeline") {
        filterBuildQueue()
        filterExecutors()
        title(pipelineName + ' ' + appName + " CI Pipeline")
        displayedBuilds(10)
        selectedJob(buildImageName)
        alwaysAllowManualTrigger()
        refreshFrequency(5)
    }

    buildMonitorView(appName +'-monitor') {
        description('All build jobs for the react-js app')
        filterBuildQueue()
        filterExecutors()
        jobs {
            regex('.*' + appName + '.*')
        }
    }
}
