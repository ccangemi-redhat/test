import java.text.SimpleDateFormat

def target_cluster_flags = ""
def version = ""
def applicationString = "${LIST}"
def applicationStringConf = "${MAP}"
def applications = applicationString.tokenize(";")
def applicationsConf = applicationStringConf.tokenize(";")

pipeline {
 agent {label 'ocp'}
 
  stages {    
   stage('CleanWS') {
    steps{
     script {
      deleteDir()
    }
   }
  }

  stage('prepare') {
   steps {
    script {
     if (!"${BUILD_BRANCH}"?.trim()) {
         currentBuild.result = 'ABORTED'
         error('Tag to build is empty')
     }
         echo "Releasing branch ${BUILD_BRANCH}"
         target_cluster_flags = "--server=$ocp_cluster_url --insecure-skip-tls-verify"
   }
  }
 }

  stage('Source checkout') {
   steps {
    checkout(
     [$class                           : 'GitSCM', branches: [[name: "${BUILD_BRANCH}"]],
      doGenerateSubmoduleConfigurations: false,
      extensions                       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/${PACKAGE_PATH}"]],
      submoduleCfg                     : [],
      userRemoteConfigs                : [[credentialsId: "${GIT_CREDENTIAL_ID}", url: "${APP_GIT_URL}"]]])
    }
   }

  stage('Check Version') {
   stages{
    stage('Maven Check'){
	 when{
	  expression{
	   file=sh(script: 'find . -name "pom.xml"', returnStdout: true).trim()
       echo file
	   return file != null && file != '';
	  }
	 }
      steps{
       script{
        pom = readMavenPom file: "${file}"
        version = pom.version
        echo "Version: ${pom.version}"
    }
   }
  }

   stage('Gradle Check'){
    when{
     expression{
	  file=sh(script: 'find . -name "build.gradle"', returnStdout: true).trim()
	  echo file
	  return file != null && file != '';
	}
   }
      steps{
       script{
       	version1 =  sh(script: "cat ${file} | grep -o 'version = [^,]*'", returnStdout: true)
        version2 = version1.split(/=/)[1].trim()
		version = version2.split (/'/) [1].trim()
        echo "|"+version+"|"
    }
   }
  }

  stage('Golang Check'){
   when {
    expression {
	  path = sh """find . -name "*version.go" -exec dirname {} \\;"""
	  file=sh(script: 'find . -name "version.go"', returnStdout: true)
	  echo file
	  return file != null && file != '';
    }
   }
      steps{
       script{
       	version =  sh(script: 'grep string ${WORKSPACE}/${PACKAGE_PATH}/${path}/version.go|awk -F"\"" \'{print $2}\'|awk -F"\"" \'{print $1}\'', returnStdout: true)
        version = "$version".substring("$version".indexOf("\"")+1, "$version".lastIndexOf("\""))
        echo "|"+version+"|"
	}
   }
  }
  
  stage('DotNet Check'){
   when {
    expression {
      return params.CSPRJ_NAME !=null && params.CSPRJ_NAME != '';
    }
   }
   steps{
    script{
     version=sh(script: 'grep Version ${WORKSPACE}/${PACKAGE_PATH}/${CSPRJ_NAME}|awk -F">" \'{print $2}\'|awk -F"<" \'{print $1}\'', returnStdout:true)
	 version = "$version".trim()
	 echo version
    }
   }
  }


  stage('NodeJs Check'){
   when{
    expression{
     file=sh(script: 'find . -name "package.json"', returnStdout: true).trim()
	 echo file
	 return file != null && file != '';
	}
   }
    steps{
     script{
      props = readJSON file: "${file}"
	  version = props.version 
      echo version
        }
       }
      }
     }
    }
  stage('Docker File') {
   steps{
    sh "cd  ${WORKSPACE}/${PACKAGE_PATH} && podman build --format docker --tag ${IMAGE_NAME} -f ${DOCKER_FILE} ."
    }
   }

  stage('Docker TAG') {
   steps {
    script {
     try{
      def updateResult = sh(script: "podman tag ${IMAGE_NAME}:latest ${DOCKER_REGISTRY}/${IMAGE_NAME}:${version}", returnStdout: true)
      echo updateResult;
        }catch(e){
          echo "Error in Create TAG:"+e.toString()
        }
       }
      }
     }

  stage('Docker Login') {
   steps {
    script {
     try{
      withCredentials([usernamePassword(credentialsId: "$NEXUS_CREDENTIALS", usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]){
       def updateResult = sh(script: "podman login ${DOCKER_REGISTRY} -u $USERNAME -p $PASSWORD ", returnStdout: true)
       echo updateResult;
          }
         }catch(e){
           echo "Error in Login:"+e.toString()
         }
        }
       }
	  }

  stage('Docker Push') {
   steps {
    script {
     try{
      def updateResult = sh(script: "podman push ${DOCKER_REGISTRY}/${IMAGE_NAME}:${version}", returnStdout: true)
      echo updateResult;
        }catch(e){
          echo "Error in Pull:"+e.toString()
        }
       }
      }
     }
  stage('Bake') {
   stages {
    stage('Configuration checkout') {
     steps {
      checkout(
       [$class           : 'GitSCM', branches: [[name: "${APP_CONF_BUILD_TAG}"]], doGenerateSubmoduleConfigurations: false,
        extensions       : [[$class: 'RelativeTargetDirectory', relativeTargetDir: 'runtime-configuration']],
        submoduleCfg     : [],
        userRemoteConfigs: [[credentialsId: "${GIT_CREDENTIAL_ID}", url: "${APP_CONF_GIT_URL}"]]])
        }
       }

  stage('deploy') {
   stages {
    stage('Config map update') {
     steps {
      script {
       withCredentials([string(credentialsId: "$OCP_SERVICE_TOKEN", variable: 'SECRET')]){
     //sh "oc set triggers deploymentconfig --manual --all --namespace=${NAMESPACE} --token=$SECRET $target_cluster_flags"
        applicationsConf.each{ app ->
         echo "Executing oc create configmap"
          sh(script: "oc create -f ${WORKSPACE}/runtime-configuration/${PATH_CONF}/Config_map/${app}.yml -o yaml  --namespace=${NAMESPACE} $target_cluster_flags --token=$SECRET||oc replace --namespace=${NAMESPACE} $target_cluster_flags --token=$SECRET -f ${WORKSPACE}/runtime-configuration/${PATH_CONF}/Config_map/${app}.yml")
        }
       }
      }
     }
    }

  stage('deployment config'){
   steps{
    script{
     withCredentials([string(credentialsId: "$OCP_SERVICE_TOKEN", variable: 'SECRET')]){
      if(Boolean.parseBoolean(env.OVERWRITE_DEPLOYMENTCONFIG)){
       applications.each{ app ->
        echo "Executing oc create e replace Deployment config ${app}-v${version}- ${PATH_CONF} ..."
        sh(script: "oc create -f ${WORKSPACE}/runtime-configuration/${PATH_CONF}/Deployment_config/${app}.yml -o yaml  --namespace=${NAMESPACE} $target_cluster_flags --token=$SECRET||oc replace --namespace=${NAMESPACE} $target_cluster_flags --token=$SECRET -f ${WORKSPACE}/runtime-configuration/${PATH_CONF}/Deployment_config/${app}.yml")
            }
           }else{
             echo "Configuration is up to date ..."
          }
         }
        }
       }
      }

  stage('Rollout') {
   steps {
    script {
     withCredentials([string(credentialsId: "$OCP_SERVICE_TOKEN", variable: 'SECRET')]){
     applications.each{ app ->
      def patchImageStream = sh(script: "oc set image dc/${app} ${app}=${DOCKER_REGISTRY}/${IMAGE_NAME}:${version} --namespace=${NAMESPACE} --token=$SECRET $target_cluster_flags", returnStdout:true)
       if (!patchImageStream?.trim()) {
           def currentImageStreamVersion = sh(script: "oc get dc ${app} -o jsonpath='{.spec.template.spec.containers[0].image}' --namespace=${NAMESPACE} --token=$SECRET $target_cluster_flags",returnStdout:true)
         if (!currentImageStreamVersion.equalsIgnoreCase("${DOCKER_REGISTRY}/${IMAGE_NAME}:${version}")) {
             echo "DeploymentConfig image tag version is: $currentImageStreamVersion but expected tag is ${version}"
             currentBuild.result = 'ERROR'
             error('Rollout finished with errors: DeploymentConfig image tag version is wrong')
            }
           }
          }
          applications.each{ app ->
           try {
		    sh "oc rollout latest ${app} --namespace=${NAMESPACE} --token=$SECRET $target_cluster_flags > result.log"
		     } catch (e) {
			  echo "Rolling out..."}
			  def rollout = readFile('result.log')
			   if (rollout?.trim()) {
			    rollout.dump()
				 if (rollout.contains('rolled out')) { echo "Rolled Out" }
				   else if (rollout.contains('already in progress')) { echo "Roll Out already triggered by DC" }
				     else { 
					  currentBuild.result = 'ERROR'
					  error('Rollout finished with errors')
				    }
			       } else { echo "Blank" }
                  }
                 }
                }
               }
              }
             }
            }
           }
          }
         }
        }
