rootProject.name = "ishare-edc"

include(":common")
include(":apps:common")

project(":apps:common").name = "apps-common"

include(":extensions:actor-extension")
include(":extensions:ishare-identity")
include(":extensions:connector-provider")
include(":extensions:connector-consumer")

include(":apps:ar")
include(":apps:consumer")
include(":apps:provider")
include(":apps:issuer")
include(":apps:wallet")

include(":init")
include(":perf")
