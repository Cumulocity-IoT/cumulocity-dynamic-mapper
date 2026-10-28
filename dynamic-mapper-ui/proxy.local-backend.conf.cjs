// Dev-server proxy that sends the Dynamic Mapper REST calls to a locally running
// dynamic-mapper-service and everything else to the Cumulocity tenant.
//
//   npm run start -- --url https://ck4.eu-latest.cumulocity.com --proxy-config proxy.local-backend.conf.cjs
//
// Passing --proxy-config replaces the devkit's default proxy, so the tenant proxy is repeated below.
const tenantUrl = process.env.C8Y_PROXY_TARGET_URL || argValue(['-u', '--url']);
const localBackend = process.env.DM_LOCAL_BACKEND || 'http://localhost:8080';

function argValue(flags) {
  const i = process.argv.findIndex(a => flags.includes(a));
  return i !== -1 ? process.argv[i + 1] : undefined;
}

if (!tenantUrl) {
  throw new Error('Pass --url <tenant> or set C8Y_PROXY_TARGET_URL');
}

module.exports = {
  // The platform strips /service/<name> before it reaches the microservice, so do the same here.
  '/service/dynamic-mapper-service': {
    target: localBackend,
    secure: false,
    changeOrigin: true,
    pathRewrite: { '^/service/dynamic-mapper-service': '' },
    logLevel: 'debug'
  },
  '/**': {
    target: tenantUrl,
    ws: true,
    secure: false,
    changeOrigin: true,
    timeout: 120000,
    proxyTimeout: 120000,
    headers: { Connection: 'keep-alive' },
    // allow cookie auth on http://localhost
    cookieDomainRewrite: 'localhost',
    bypass: req => (/\/ng-cli-ws/.test(req.url) ? req.url : undefined),
    onProxyRes: proxyRes => {
      const cookies = proxyRes.headers['set-cookie'];
      if (cookies) {
        proxyRes.headers['set-cookie'] = cookies.map(c => c.replace(/;\s{0,}secure/gi, ''));
      }
      const { location } = proxyRes.headers;
      if (location && proxyRes.statusCode === 301) {
        proxyRes.headers.location = location.replace(/https?:\/\/[^/]+/, '');
      }
    }
  }
};
