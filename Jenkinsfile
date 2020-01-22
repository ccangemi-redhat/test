try {
   timeout(time: 20, unit: 'MINUTES') {
      node('nodejs') {
          stage('build') {
            openshift.withCluster() {
               openshift.withProject() {
                  def bld = openshift.startBuild('nodejs-mongodb-example')
                  bld.untilEach {
                    return it.object().status.phase == "Running"
                  }
                  bld.logs('-f')
               }  
            }
          }
          stage('deploy') {
            openshift.withCluster() {
              openshift.withProject() {
                def dc = openshift.selector('dc', 'nodejs-mongodb-example')
                dc.rollout().latest()
              }
            }
          }
        }
   }
} catch (err) {
   echo "in catch block"
   echo "Caught: ${err}"
   currentBuild.result = 'FAILURE'
   throw err
}          
