pipeline {
    agent any

    options {
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        disableConcurrentBuilds()
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Build') {
            steps {
                sh './gradlew clean build -x test --no-daemon'
            }
        }

        stage('Unit tests (no Docker required)') {
            steps {
                // Decision token tests and core-mock scope-token tests run without a database.
                sh './gradlew :decision:test :core-mock:test --no-daemon'
            }
        }

        stage('Channel integration tests (Testcontainers Postgres)') {
            steps {
                script {
                    def dockerAvailable = sh(script: 'docker info > /dev/null 2>&1', returnStatus: true) == 0
                    if (dockerAvailable) {
                        sh './gradlew :channel:test --no-daemon'
                    } else {
                        echo 'Docker is not available on this agent; skipping Testcontainers-based channel tests.'
                    }
                }
            }
        }

        stage('Package') {
            steps {
                sh './gradlew shadowJar --no-daemon'
            }
        }
    }

    post {
        always {
            junit allowEmptyResults: true, testResults: '**/build/test-results/test/*.xml'
            archiveArtifacts artifacts: '**/build/libs/*.jar', fingerprint: true
        }
        failure {
            echo 'Build failed — review the test reports and console output.'
        }
    }
}
