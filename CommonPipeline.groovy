//Author : Mahesh Hadimani
//Created: 20-Apr-2019
//Modified: 05-Aug-2019

@NonCPS
import hudson.EnvVars
import groovy.json.JsonSlurper
import groovy.transform.Field

 
@Field def slave, checkout, build, artifacts, name, platform, props, environment, mail, startParallel, failParallel, cleanWorkspace, docker
@Field ArrayList<String> allStages, parallelStages
@Field def isParallelInProgress = false

@Field def cwd = "./"

//parses json and converts json to a map
def getPipelineJson(json) {
    def jsonSlurper = new JsonSlurper()
    return new HashMap<>(jsonSlurper.parseText(json))
}

// converts lazymap to hashmap
def convertLazyMap(lazyMap) {
    hMap = new HashMap<>();
    lazyMap.each { prop ->
        if (prop.value instanceof java.util.ArrayList) {
            hMap[prop.key] = prop.value.collect  { it -> getHashMapFromLazyMap(it) }
        } else {
           hMap[prop.key] = prop.value 
        }
    }
    return hMap
}

def getHashMapFromLazyMap(lazyMap) {
    if (lazyMap instanceof java.lang.String) {
        return lazyMap
    }
    m = new HashMap<>();
    for ( prop in lazyMap ) {
        m[prop.key] = prop.value
    }
    return m
}

def getStageConfig(json, stage) {
    if (name == null || slave == null || allStages == null || props == null || environment == null || mail == null || startParallel == null || failParallel == null) {
        print("Getting stage configuration for stage - ${stage}")
        def res = getPipelineJson(json)
        res = new HashMap<>(res)
        print("Got pipeline json  ==> ${res}")

        for (item in res) {
            if (!item.value.isEmpty()) {
                item.value = new HashMap<>(item.value)
                name = item.key
                if (item.value["active"] == true) {
                    print("Setting data for all fields")
                    platform = item.value["platform"]
                    allStages = item.value["stages"].keySet().collect()
                    slave = item.value["agent"]
                    startParallel = item.value["startParallelAfterStage"]
                    failParallel = item.value["parallelFailEarly"]
                    props = convertLazyMap(item.value["properties"])
                    environment = convertLazyMap(item.value["environment"])
                    mail = convertLazyMap(item.value["mail"])
                    parallelStages = item.value["stages"].findAll { it -> (it.value.parallel == true || (it.value.parallel != null && (!it.value instanceof List))) }.keySet().collect()
                    docker = convertLazyMap(item.value["docker"])
                } else {
                    print("NO ACTIVE PIPELINE DEFINITION FOUND!")
                }
            }
        }
    }
    switch (stage.toLowerCase()) {
        case "name" : return name
        case "slave" : return slave
        case "stages" : return allStages
        case "properties" : return props
        case "environment" : return environment
        case "mail" : return mail
        case "parallel" : return parallelStages
        case "docker" : return docker
        default: returnCustomStage(json, stage)
    }
}


// return custom stages
def returnCustomStage(json, stage) {
    def res = getPipelineJson(json)

    res = new HashMap<>(res)
    for (item in res) {
        if (!item.value.isEmpty()) {
            item.value = new HashMap<>(item.value) 
            if (item.value["active"] == true) {
                print("Returning custom stage data for - ${stage}")
                if (item.value["stages"][stage] instanceof List){
                    ArrayList<String> list = (item.value["stages"][stage].collect { it -> convertLazyMap(it) })
                    return list
                } else {
                    return convertLazyMap(item.value["stages"][stage])
                }
            }
        }
    }
}

//execute parallel
def verifyAndRunInParallel(json, pCount, pStages, slave, envMap) {
    if (!isParallelInProgress && pCount > startParallel && startParallel != null) {
        print("Executing all parallel stages...")
        isParallelInProgress = true
        def parallelMap = [:]
        for (ps in pStages) {
            def scriptParams = getStageConfig(json, ps)
            parallelMap[ps] = scriptParams
        }
        print("Executing stages in parallel - ${pStages}]")
        print("Found parallelFailEarly - ${failParallel}")
        if (failParallel == null) {
            failParallel = true
        }
        executeParallelStage(slave, parallelMap, failParallel, envMap)
    }
}

 

// Add pipelines and execute
def addStagesAndExecutePipeline(json) {
    def isParallelInProgress = false
    try {
        print("Executing pipeline")
        
        //parallel change
        allStages = getStageConfig(json, "stages")
        print("All stages - ${allStages}")
        List pStages = getStageConfig(json, "parallel")

        if (pStages != []) {
            // Empty pStages if the parallel stages == 1
            if (pStages.size() == 1) {
                print("Found only one parallel stage - REMOVING from parallel list")
                pStages = [];
            } else {
                print("PARALLEL STAGES => ${pStages}")
            }
        }
        //get docker configuration
        docker = getStageConfig(json, "docker")
        def slaveNode = getStageConfig(json, "slave")

        print("Found slave node - ${slaveNode}")
        def jobName = getStageConfig(json, "name")
        // Add node data
        node (slaveNode) {
            cwd = WORKSPACE
            currentBuild.displayName = "${jobName}_${BUILD_NUMBER}"
            currentBuild.description = "Pipeline for - ${jobName}"
            currentBuild.result = "SUCCESS"
        }

        // set properties for pipeline
        def pMap = getStageConfig(json, "properties")
        print("Found properties for pipeline - ${pMap}")
        cleanWorkspace = pMap["cleanWorkspace"]
        if (pMap["parameters"]) {
            addParametersForPipline(pMap["parameters"])
        }

        //get environment details for pipeline
        def envMap = getStageConfig(json, "environment")
        print("Found environment for pipeline - ${envMap}")

        //allStages = allStages - pStages //remove all the parallel stages from allStages list
        print("ALL STAGES => ${allStages}")
        if (allStages != null) {
            def stageType = null
            def parallelCount = 0
            for (eStage in allStages) {
                print("FOUND STAGE => ${eStage}")
                parallelCount += 1
                //verify and run in parallel
                verifyAndRunInParallel(json, parallelCount, pStages, slaveNode, envMap)

                if (eStage in pStages) {
                    print("Skipping executing stage as it's executed in parallel - ${eStage}")
                    continue
                }
                stageType = getStageType(json, eStage)
                // checkout
                if (stageType != null && stageType == "checkout") {
                    def cs = getStageConfig(json, eStage)
                    print ("Found checkout stage - ${cs}")
                    checkoutStage(getSlaveNodeForStage(cs, slaveNode), cs, eStage);
                }
                // artifacts
                else if (stageType != null && stageType == "artifact") {
                    def art = getStageConfig(json, eStage)
                    print("Found artifact stage - ${art}")
                    artifactStage(getSlaveNodeForStage(art, slaveNode), art, eStage)
                }

                // reports
                else if (stageType != null && stageType == "reports") {
                    def reportsMap = getStageConfig(json, eStage)
                    print("Found Report stage - ${reportsMap}")
                    reportsStage(slaveNode, reportsMap, eStage)
                }

                // deploy to cf
                else if (stageType != null && stageType == "deploy") {
                    def deployMap = getStageConfig(json, eStage)
                    print("Found Deploy stage - ${deployMap}")
                    deployCf(getSlaveNodeForStage(deployMap, slaveNode), deployMap, envMap, eStage)
                }

                // warnings
                else if (stageType != null && stageType == "warnings") {
                    def wMap = getStageConfig(json, eStage)
                    print("Found Warnings stage - ${wMap}")
                    performCheckForCompilerWarnings(getSlaveNodeForStage(wMap, slaveNode), wMap, eStage)
                }

                // docker
                else if (stageType != null && stageType == "docker") {
                    def dockerMap = getStageConfig(json, eStage)
                    print("Found Docker stage - ${dockerMap}")
                    invokeDockerStage(getSlaveNodeForStage(dockerMap, slaveNode), dockerMap, eStage)
                }

                // handle all custom stage
                else {
                    def stageParams = getStageConfig(json, eStage)
                    print("Found custom stage - ${stageParams}")
                    def decla = stageParams["declarative"]
                    if ( decla != null && decla == true) {
                        executeDeclarativeStage(slaveNode, stageParams, eStage)
                    } else {
                        executeStage(getSlaveNodeForStage(stageParams, slaveNode), stageParams, envMap, eStage)
                    }
                }
                // for end
            }
        }

    } catch(err) {
        currentBuild.result = "FAILURE"
        throw err
    } finally {
        // clean workspace
        if (cleanWorkspace != null && cleanWorkspace) {
            executeStagesInNodeOrDocker(slave, { cleanWs() } )
            //node(slave) { cleanWs() }
        }

        def mailParams = getStageConfig(json, "mail")
        print("Found mail stage - ${mailParams}")
        if (mailParams != [:]) {
            sendEmailNotification(mailParams)
        }
    }
}

 

// logic for checkout stage
def checkoutStage(n, mapCheckout, stageName){
    def scm = mapCheckout["scm"].toLowerCase()
    def repo = mapCheckout["repo"]
    def branch = mapCheckout["branch"]
    def credsId = mapCheckout["credsId"]
    def refSpec = mapCheckout["refSpec"]
    def params = mapCheckout["custom"]
    def dir = mapCheckout["dir"]
    def sparseCheckoutPaths = mapCheckout["sparseCheckoutPath"]

    if ( scm == "git") {
        if (params != "") {
            //handle the case of COMMIT ID
            branch = getProperty(mapCheckout["custom"])   
            print ("Got custom param -> ${mapCheckout["custom"]} -> with value - ${branch}")
        }
        checkoutGitRepo(n, repo, branch, credsId, refSpec, dir, sparseCheckoutPaths, stageName)
    } else if (scm == "tfvc") {
        checkoutTFVCRepo(n, repo, branch, credsId, stageName)
    } else {
        error "UNKNOWN SCM - ${scm}"
    }
}

// checkout from a git repo
def checkoutGitRepo(n, repo, branch, credsId, refSpec, dir, sparseCheckoutPaths, stageName) {
    List<hudson.plugins.git.extensions.impl.SparseCheckoutPath> sparsePaths = new ArrayList<>();
    sparseCheckoutPaths.collect { it -> sparsePaths.add([$class:'SparseCheckoutPath', path: it]) }
    print("*** sparsePaths -> $sparsePaths")
    executeStagesInNodeOrDocker(n, {
        stage(stageName) {
            checkout(
                [$class: "GitSCM",
                branches: [[name: branch]],
                doGenerateSubmoduleConfigurations: false,
                extensions: [
                    [$class: 'RelativeTargetDirectory', relativeTargetDir: dir],
                    [$class: 'SparseCheckoutPaths',  sparseCheckoutPaths: sparsePaths]
                ],
                submoduleCfg: [],
                userRemoteConfigs: [[credentialsId: credsId, url: repo, refspec: refSpec]]])
        }
    })
}

// checkout from a TFVS repo
def checkoutTFVCRepo(n, repo, branch, credsId, stageName) {
    executeStagesInNodeOrDocker(n, {
        stage(stageName) {
            checkout(
                [$class: 'TeamFoundationServerScm',
                serverUrl: repo,
                projectPath: branch,
                useOverwrite: true,
                useUpdate: true,
                credentialsConfigurer: [$class: 'AutomaticCredentialsConfigurer']
            ])
        }
    })
}

def artifactStage(n, mapArtifact, stageName){
    executeStagesInNodeOrDocker(n, {
        stage(stageName) {
            echo "Publishing/Downloading artifacts ..."
            publishArtifact(mapArtifact)
        }
    })
}

def reportsStage(n, reportsMap, stageName){
    executeStagesInNodeOrDocker(n, {
        stage(stageName) {
            echo "Publishing HTML Reports ..."
            publishReports(n, reportsMap)
        }
    })
}

def deployCf(n, deployMap, envMap, stageName) {
    executeStagesInNodeOrDocker(n, {
        stage(stageName) {
            echo "Deploying artifacts to cloud foundry ..."
            deployToCf(n, deployMap, envMap, stageName)
        }
    })
}

def executeDeclarativeStage(n, decParams, stageName) {
    def listSteps = decParams["script"]
    executeStagesInNodeOrDocker(n, {
        stage(stageName) {
            echo "Executing declarative steps ..."
            //steps {
                //script {
                   // listSteps.join("\n")
                    // for (s in listSteps) {
                    //     print("S => ${s}")
                    //     "${s}"
                    // }
                //}
            //}                    
        }
    })
}

// Helper methods

// get slave node if configured for each stage else return the root agent configured
def getSlaveNodeForStage(params, node) {
    if (params["agent"] == null || params["agent"] == "") {
        return node
    } else {
        print("Returning agent defined in the stage - ${params["agent"]}")
        slave = params["agent"]
        return params["agent"]
    }
}

//return stage properties
def getStageType(json, stage) {
    def stageProp = returnCustomStage(json, stage)
    print("stage prop => ${stageProp}")
    if (stageProp instanceof List) {
        if (stageProp.get(0)["reportName"]) {
            return "reports"
        }
    } else if (stageProp["scm"]) {
        return "checkout"
    } else if (stageProp["filePattern"] && stageProp["targetPath"]) {
        return "artifact"
    } else if (stageProp["credsIdForCF"]) {
        return "deploy"
    } else if (stageProp["parsers"]) {
        return "warnings"
    } else if (stageProp["dockerServer"]) {
        return "docker"
    } else {
        return null
    }
}

 

// common stage method to execute stages within environment
def executeStage(n, params, envMap, stageName) {
    def envList = []
    envMap.each { k, v -> envList.add("${k}=${v}") }
    print ("Using environment for stages/steps ==> ${envList}")

    //handle the case of in-stage platform - for cross platform build, test machines
    def os = params["platform"]
    if (os != null) {
        platform = os.toLowerCase()
        cwd = executeStagesInNodeOrDocker(n, { WORKSPACE })
        //cwd = node(slave) { WORKSPACE }
    }

    executeStagesInNodeOrDocker(n, {
        withEnv(envList) {
            stage(stageName) {
                dir(cwd) {
                    executeMultiSteps(params)
                }
            }
        }
    })
}

// parallel stage execution
def executeParallelStage(n, scanMap, fp, envMap) {
    def map = [:]
    scanMap.each { k, v ->
        print(k)
        print(v)
        map[k] = { executeStage(n, v, envMap, k) }
    }
    map.failFast = fp
    parallel map
}

// method to execute multiple steps provided in a script block
def executeMultiSteps(params) {
    def steps = []
    def listSteps = params["script"]

    // interpolate all env variables
    for (item in listSteps) {
        if (item.contains("BUILD_NUMBER") || item.contains("BUILD_TIMESTAMP") || item.contains("WORKSPACE") || item.contains("JOB_NAME")) {
            print("Found jenkins env variable => ${item}")
            steps.add(getJenkinsEnvVariable(item))
        } else {
            steps.add(item)
        }
    }

    //setting executeInOneShell to true by default
    params["executeInOneShell"] = true
    print("STEPS => ${steps}")

    if (params["executeInOneShell"]) {
        print("Executing steps in one shell")
        platform = platform.toLowerCase()  
        if (platform == "linux") {
            sh "${steps.join('\n')}"
        } else {
            bat "${steps.join('\n')}"
        }
    } else {
        // this block must be removed as this is dead code
        // Execute multiple steps in seperate shell
        for (item in steps) {
            print("Executing step - ${item}")
            if (item.contains("cd ")) {
                cwd = item.split("cd ")[1]
            }
            dir(cwd) {
                platform = platform.toLowerCase()
                if (platform == "linux" && !item.contains("cd ")) {
                    sh "${item}"
                } else if (!item.contains("cd ")) {
                    bat "${item}"
                }
            }
        }
    }
}

// publishes the artifacts to the artifactory, uses the jfrog artifactory plugin
def publishArtifact(artifactData, download=false) {
    if (artifactData != {}) {
        def aServer = Artifactory.newServer url: "${artifactData['server']}", credentialsId: "${artifactData['credsId']}"
        def uploadSpec = """{
                    "files": [{
                    "pattern": "${artifactData['filePattern']}",
                    "target": "${artifactData['targetPath']}"
                    }]
            }"""
        aServer.bypassProxy = artifactData['bypassProxy']
        if (download || artifactData["action"] == "download") {
            print("Downloading artifacts from artifactory")
            aServer.download(uploadSpec)
        } else {
            aServer.upload(uploadSpec)
        }
    }
}


def addPropertiesForPipeline(propsMap) {
    def sched = propsMap["schedule"]
    def poll = propsMap["poll"]

    // apply default properties for all jobs
    properties([
        disableConcurrentBuilds(),
        [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '15']]
    ])
    if (sched != null) {
        if (sched != "") {
            print("Setting schedule for pipeline...")
            properties([
                pipelineTriggers([[$class: "TimerTrigger", spec: "${sched}"]]),
                disableConcurrentBuilds(),
                [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '15']]
            ])
        }
    }
    if (poll != null) {
        if (poll != "") {
            print("Setting poll property for pipeline...")
            properties([
                pipelineTriggers([[$class: "SCMTrigger", scmpoll_spec: "${poll}"]]),
                disableConcurrentBuilds(),
                [$class: 'BuildDiscarderProperty', strategy: [$class: 'LogRotator', numToKeepStr: '15']]
            ])
        }
    }
}

def addParametersForPipline(paramMap) {
    List<ParameterDefinition> paramDefn = new ArrayList<>()
    for (item in paramMap) {
        param = item.split(":")
        if ( param[1] == "STRING") {
            paramDefn.add(stringParam(defaultValue: param[2], name: param[0]))
        } else if (param[1] == "BOOLEAN") {
            paramDefn.add(booleanParam(defaultValue: param[2], name: param[0]))
        } else {
            error "Unknown parameter defined - ${item}"
        }
    }

    if (paramDefn) {
        properties([parameters(paramDefn)])
    }
}


// sends email notification - uses the email extension plugin
def sendEmailNotification(recipientMap) {
    print("Sending mail notification for all the recipients!")
    if (recipientMap != [:] || recipientMap["to"] == "") {
        def emailBody = '''${SCRIPT, template="groovy-html.template"}'''
        def emailSubject = "${env.JOB_NAME} - Build# ${env.BUILD_NUMBER} - ${currentBuild.result}"
        emailext(
            mimeType: "text/html",
            replyTo: "${recipientMap["from"]}",
            subject: emailSubject,
            to: "${recipientMap["to"]}",
            body: emailBody,
            recipientProviders: [[$class: 'CulpritsRecipientProvider'],[$class: 'RequesterRecipientProvider']]
        )
    }
}

// return values for jenkins environment variables
def getJenkinsEnvVariable(var) {
    if (var.contains("BUILD_NUMBER")) {
        return var.replaceAll("BUILD_NUMBER", getProperty("BUILD_NUMBER"))
    } else if (var.contains("BUILD_TIMESTAMP")) {
        return var.replaceAll("BUILD_TIMESTAMP", getProperty("BUILD_TIMESTAMP"))
    } else if (var.contains("BUILD_NUMBER") && var.contains("BUILD_TIMESTAMP")) {
        var = var.replaceAll("BUILD_NUMBER", getProperty("BUILD_NUMBER"))
        return var.replaceAll("BUILD_TIMESTAMP", getProperty("BUILD_TIMESTAMP"))
    } else if (var.contains("WORKSPACE")) {
        return var.replaceAll("WORKSPACE", getProperty("WORKSPACE"))
    } else if (var.contains("JOB_NAME")) {
        return var.replaceAll("JOB_NAME", getProperty("JOB_NAME"))
    } else {
        return "UNSUPPORTED ENV VARIABLE - ${var}"
    }
}

// publish html reports - uses the HTML publisher plugin
def publishReports(n, reportsMap) {
    if (reportsMap != {}) 
        for (report in reportsMap) {
            publishHTML target: [
                allowMissing: false,
                alwaysLinkToLastBuild: false,
                keepAll: true,
                reportDir: report["reportDir"],
                reportFiles: report["reportFiles"],
                reportName: report["reportName"]
            ]
        }
    }
    jacoco()
} 

//removes directory and sub-directories
def deleteAllDir(dir) {
    print("Deleting directory - ${dir}")
    if (fileExists(dir)) {
        if (platform.toLowerCase() == 'linux') {
           sh "rm -rf ${dir}"
        } else {
            dir = dir.split("/")[0]
            bat "rmdir /S /Q ${dir}"
        }   
    } else {
        print("Could not find directory - ${dir} to delete!")
    }  
}

def downloadArtifactViaHTTP(artMap) {
    httpRequest authentication: artMap["creds"],
        ignoreSslErrors: true,
        responseHandle: 'NONE',
        url: artMap["url"],
        outputFile: artMap["artifactName"]
}


def performCFPush(deployMap, envMap) {
    def endpoint = deployMap["cfEndpoint"]
    def org = deployMap["org"]
    def space = deployMap["space"]

    def envList = []
    envMap.each { k, v -> envList.add("${k}=${v}") }
    print ("Using environment for stages/steps ==> ${envList}")

    withEnv(envList) {
        withCredentials(
            [[$class: 'UsernamePasswordMultiBinding',
                credentialsId: deployMap["credsIdForCF"],
                usernameVariable: 'USERNAME',
                passwordVariable: 'PASSWORD']]) {
                    if (platform.toLowerCase() == "windows") {
                        bat "cf login -a $endpoint -u $USERNAME -p $PASSWORD t -o ${org} -s ${space}"
                        bat "cf push"
                    } else {
                        sh "cf login -a $endpoint -u $USERNAME -p $PASSWORD t -o $org -s $space"
                        sh "cf push"
                    }              
                }
    }
}

def performCheckForCompilerWarnings(n, warnMap, stageName) {
    if (warnMap != null) {
        if (warnMap["parsers"] == []) {
            error "NO PARSERS found to scan for compiler warnings!"
        } else {
            executeStagesInNodeOrDocker(n, {
                stage(stageName) {
                    for (parser in warnMap["parsers"]) {
                        warnings canComputeNew: false,
                            canResolveRelativePaths: false,
                            categoriesPattern: '',
                            consoleParsers: [[parserName: parser]],
                            defaultEncoding: '',
                            excludePattern: warnMap["excludePattern"],
                            messagesPattern: warnMap["messagesPattern"],
                            failedTotalAll: warnMap["failThreshold"],
                            unstableTotalAll: warnMap["unstableThreshold"],
                            healthy: '',
                            unHealthy: '',
                            includePattern: ''
                    }   
                }
            })
        }
    }
}

def invokeDockerStage(n, dockerMap, stageName) {
    node(n) {
        if (dockerMap != null) {
            withDockerServer([uri: dockerMap["dockerServer"]]) {
                withDockerRegistry([credentialsId: dockerMap["dockerRegistryCreds"], url: dockerMap["dockerRegistry"]]) {
                    if (dockerMap["args"] == [] || !dockerMap["args"] instanceof List) {
                        error "ARGS is empty or not of type LIST!"
                    }
                    if (platform.toLowerCase() == "windows") {
                        bat "${dockerMap['args'].join('\n')}"
                    } else {
                        sh "${dockerMap['args'].join('\n')}"
                    }  
                }
            }
        } else {
            error "Docker stage configuration is incorrect, got - ${dockerMap}"
        }   
    }
}

def executeStagesInNodeOrDocker(n, callback) {
    if (docker) {
        print("*** Executing stage in docker!")
        node {
            withDockerServer([uri: docker["dockerServer"], credentialsId: docker["dockerServerCreds"]]) {
                withDockerRegistry([credentialsId: docker["dockerRegistryCreds"], url: docker["dockerRegistry"]]) {
                    withDockerContainer(args: docker["args"], image: docker["image"]) {
                        callback()
                    }
                }
            }
        }
    } else {
        node(n) {
            callback()
        }
    }
}