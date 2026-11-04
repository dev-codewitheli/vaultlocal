rootProject.name = "vaultlocal"

include(
    "domain",
    "application",
    "adapters:crypto",
    "adapters:persistence-sqlite",
    "adapters:ui-javafx",
    "app",
)
