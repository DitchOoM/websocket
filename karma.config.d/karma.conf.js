config.client = config.client || {}
config.client.mocha = config.client.mocha || {}
// Increase timeout for large payload tests (case9 tests with 16MB payloads)
// and heavy compression test batches (category 12/13)
config.client.mocha.timeout = 660000 // 11 minutes (matches Node.js mocha config)

// Prevent Karma from killing the browser during long-running compression tests
config.browserNoActivityTimeout = 660000
config.browserDisconnectTimeout = 60000
