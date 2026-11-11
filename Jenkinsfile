// CI/CD for the edge control plane. Runs on the local Jenkins from scripts/jenkins-up.sh,
// which mounts the Docker socket and a kubeconfig for the kind cluster.
pipeline {
  agent any
  options {
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
    disableConcurrentBuilds()
  }
  parameters {
    booleanParam(name: 'SIMULATE_SMOKE_FAILURE', defaultValue: false,
        description: 'Fail the smoke test on purpose, to exercise the automatic rollback')
  }
  environment {
    // deployment name -> image; each service is built, deployed and rolled back together
    SERVICES = "control-plane=edge-control-plane analytics=edge-analytics llm=edge-streaming"
  }
  stages {
    stage('Build and unit test') {
      // Each Dockerfile compiles and runs its unit tests; a failing test fails the image build.
      parallel {
        stage('control-plane') { steps { sh 'docker build -t edge-control-plane:$BUILD_NUMBER control-plane' } }
        stage('analytics')     { steps { sh 'docker build -t edge-analytics:$BUILD_NUMBER analytics' } }
        stage('streaming')     { steps { sh 'docker build -t edge-streaming:$BUILD_NUMBER streaming' } }
      }
    }
    stage('Integration test') {
      steps {
        // Real Envoy and PostgreSQL in containers (Testcontainers), plus the full app per probe
        sh './scripts/integration-test.sh'
      }
      post {
        always {
          junit testResults: 'control-plane/target/*-reports/*.xml', allowEmptyResults: true
        }
      }
    }
    stage('Deploy to kind') {
      steps {
        // A unique tag per build, so a bad deploy can be undone with "kubectl rollout undo".
        sh '''
          for s in $SERVICES; do
            deploy=${s%%=*}; image=${s#*=}
            kind load docker-image $image:$BUILD_NUMBER --name edge
            kubectl -n edge set image deployment/$deploy "*=$image:$BUILD_NUMBER"
            kubectl -n edge annotate deployment/$deploy --overwrite \
              kubernetes.io/change-cause="jenkins build $BUILD_NUMBER, commit $GIT_COMMIT"
          done
          for s in $SERVICES; do
            kubectl -n edge rollout status deployment/${s%%=*} --timeout=240s
          done
        '''
      }
    }
    stage('Smoke test') {
      steps {
        script {
          try {
            withEnv(["SIMULATE_FAILURE=${params.SIMULATE_SMOKE_FAILURE ? 1 : 0}"]) {
              sh './scripts/smoke-test.sh'
            }
          } catch (err) {
            echo 'Smoke test failed: rolling every service back to the previous build'
            sh '''
              for s in $SERVICES; do kubectl -n edge rollout undo deployment/${s%%=*}; done
              for s in $SERVICES; do kubectl -n edge rollout status deployment/${s%%=*} --timeout=240s; done
              kubectl -n edge get deploy control-plane analytics llm \
                -o custom-columns='DEPLOYMENT:.metadata.name,IMAGE:.spec.template.spec.containers[0].image'
            '''
            throw err
          }
        }
      }
    }
  }
  post {
    success { echo "Build ${env.BUILD_NUMBER} (${env.GIT_COMMIT.take(7)}) is live on kind" }
    failure { echo 'Pipeline failed. If it got past deploy, the previous build was restored.' }
  }
}
