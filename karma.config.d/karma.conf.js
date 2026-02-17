config.client = config.client || {}
config.client.mocha = config.client.mocha || {}
// Increase timeout for large payload tests (case9 tests with 16MB payloads)
config.client.mocha.timeout = 120000 // 2 minutes

