plugins {
    id("app.cash.sqldelight") version "2.3.2"
}

sqldelight {
    databases {
        register("Database") { // The generated Database class name
            packageName.set("sqldelight")
        }
    }
}