# provider-user-position-migrator

Utility to migrate COMPLETED ProviderUserPositions entities to Transaction entities for a user

Change these values for the user you want to migrate

    val userId = "d5d45294-6be0-4fe2-8190-00c70b8291c8"
    val walletSavingId = "f9cd28ad-b5cb-46ed-aadd-bceddbf785c6"
    val merchantId = UUID.fromString("5399b8c1-d949-404e-99c3-25a7b8e82486")
    val walletAccountId = UUID.fromString("bb85a2a4-8d52-4e55-b0e8-f79209fe7c49")

Run the following commands in your terminal to set the AWS environment variables.

    export AWS_ACCESS_KEY_ID="ABC"
    export AWS_SECRET_ACCESS_KEY="ABC123"
    export AWS_SESSION_TOKEN="ABC123"

Build & Run:

    mvn clean package 

    java -jar target/provider-user-position-migrator-1.0.0.jar
