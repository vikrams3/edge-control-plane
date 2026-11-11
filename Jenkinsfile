// CI/CD for the edge control plane. Runs on the local Jenkins from scripts/jenkins-up.sh,
// which mounts the Docker socket and a kubeconfig for the kind cluster.
pipeline {
  agent any
  options {
    timestamps()
    timeout(time: 30, unit: 'MINUTES')
    disableConcurrentBuilds()
  }
  environment {
    IMAGE = "edge-control-plane"
  }
  stages {
    stage('Build and unit test') {
      steps {
        // The Dockerfile compiles and runs the unit tests; a failing test fails the image build.
        sh 'docker build -t $IMAGE:$BUILD_NUMBER -t $IMAGE:dev control-plane'
      }
    }
    stage('Integration test') {
      steps {
        // Real Envoy and PostgreSQL in containers (Testcontainers)
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
          kind load docker-image $IMAGE:$BUILD_NUMBER --name edge
          kubectl -n edge set image deployment/control-plane control-plane=$IMAGE:$BUILD_NUMBER
          kubectl -n edge annotate deployment/control-plane --overwrite \
            kubernetes.io/change-cause="jenkins build $BUILD_NUMBER, commit $GIT_COMMIT"
          kubectl -n edge rollout status deployment/control-plane --timeout=180s
        '''
      }
    }
    stage('Smoke test') {
      steps {
        script {
          try {
            sh './scripts/smoke-test.sh'
          } catch (err) {
            echo 'Smoke test failed: rolling the control plane back to the previous build'
            sh 'kubectl -n edge rollout undo deployment/control-plane && kubectl -n edge rollout status deployment/control-plane --timeout=180s'
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
