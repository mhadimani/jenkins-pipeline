# jenkins-pipeline
A common jenkins pipeline that can be used across projects in an org

# Introduction 
Common Pipeline lib to create a pipeline with custom stages. The definition goes in a JSON format, so enables easy addition/removal of stages from the pipeline

# Getting Started
Add this lib as a shared library under Jenkins --> Manage jenkins --> Configure System.
Add the below snippet to the pipeline script in the jenkins job or add the same to the GIT repo for a multi branch project

```
#!groovy

def jsonDefn = '''
{
    <JSON>
}
'''

library('pipeline-lib')
CommonPipeline.addStagesAndExecutePipeline(jsonDefn)
```

#Pre-requisites:
The lib depends on the following list of plugins -
1. tfs
2. pipeline-stage-view
3. pipeline-model-definition
4. jacoco
5. http_request
6. htmlpublisher
7. git-client
8. git
9. artifactory
10. build-timestamp
11. build-pipeline-plugin
12. ws-cleanup
13. warnings plugin

# Sample Json Format
```
{
	"pipeline_test" : {
		"platform" : "linux",
		"agent" : "",
		"startParallelAfterStage": 3,
		"parallelFailEarly" : true,
		"docker" : {
			"dockerServer" : "tcp://127.0.0.1:4243",
			"dockerServerCreds: "",
			"dockerRegistry" : "",
			"dockerRegistryCreds": "",
			"image": "<image>",
			"args": ""
		},
		"properties" : {
		    "schedule" : "",
			"poll" : "",
			"cleanWorkspace" : true,
			"parameters" : [
				"VAR1:STRING:test",
				"VAR2:BOOLEAN:false"
			]
		},
		"environment" : {
            "http_proxy": "x.x.x.x:xxxx",
            "https_proxy": "x.x.x.x:xxxx"
		},
		"mail" : {
            "from": "xxx@abc.com",
            "to": "xxx@abc.com"
		},
		"stages" : {
			"code checkout" : {
				"scm" : "git",
				"repo" : "",
				"branch" : "feature-b",
				"custom" : "",
				"refSpec": "",
				"sparseCheckoutPath": ["", ""]
				"credsId" : "mycreds"
			},
			"build" : {
				"script" : [
					"mvn clean install -DskipTests"
				]
			},
			"publish artifacts" : {
					"server" : "<Artifactory repo>",
					"credsId" : "<CREDS>",
					"filePattern": "*.jar",
					"targetPath" : "<REPO>",
					"bypassProxy": true
			},
			"unit tests" : {
				"parallel": true,
				"script" : [
					"mvn clean install"
				]
			},
			"func tests" : {
				"parallel": true,
				"script" : [
					"mvn clean install"
				]
			},
			"publish reports" : [
    			{
    			     "reportDir" : "target/surefire-reports",
    			     "reportFiles" : "index.html",
    			     "reportName" : "SOME REPORTS"
    			},
    			{
    			     "reportDir" : "target/surefire-reports",
    			     "reportFiles" : "index.html",
    			     "reportName" : "SOME REPORTS AGAIN"
    			}
    		],
			"warnings" : {
				parsers : [
					"Java Compiler (javac)",
					"Maven"
				],
				"excludePattern": "",
				"messagesPattern": "",
				"failThreshold" : "90",
				"unstableThreshold": "80"
			},
			"docker" : {
				"dockerServer" : "tcp://127.0.0.1:4243",
				"dockerRegistry" : "REGISTRY_URL",
				"dockerRegistryCreds": "CREDS_ID_FOR_DOCKER_REGISTRY",
				"args": [
					"echo a",
					"echo b"
				]
			}
		},
		"active" : true
	}
}
```
# Environment variables
The following are the environmental variables supported 
    - BUILD_NUMBER
    - BUILD_TIMESTAMP
    - WORKSPACE
    - JOB_NAME
When configuring the stage, the commands/script can make use of the environment variables and these would be interpolated.
Ex: "Security scan" : {
		"script" : [
            "WORKSPACE/scripts/some_script.sh"
		]
    }


