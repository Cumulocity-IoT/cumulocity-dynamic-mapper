// to run with proxy along with local API server, use command `ng serve -u <tenant url> --proxy-config ./proxy.conf.mjs`
import * as process from 'process';

function getTargetUrl(args) {
  const urlArgumentIndex = args.findIndex((a) => a === '-u' || a === '--url');
  if (urlArgumentIndex !== -1 && urlArgumentIndex < args.length - 1) {
    return args[urlArgumentIndex + 1];
  }
  return null;
}

const target = getTargetUrl(process.argv);

if (target) {
  console.log(`Target url for proxy: ${target}`);
} else {
  console.error('Target url not found or --url (alias -u) argument is missing');
  process.exit(1);
}

export default [
  {
    path: ['/service/siren/subscriptions/**'],
    target: 'http://localhost:3000',
    pathRewrite: { '^/service/siren': '' }, // remove '/service/siren' part from call to test against local server
    changeOrigin: true,
    secure: false,
    ws: true
  },
  {
    target,
    ws: true,
    secure: false,
    changeOrigin: true,
    timeout: 120000,
    proxyTimeout: 120000,
    path: ['**'],
    /**
     * The following two lines are used to allow to use cookie-auth
     * also in un-secure environments like local-development. It removes
     * the secure flag and rewrites the domain for the cookie.
     *
     * You must never use this setting in production!
     */
    cookieDomainRewrite: 'localhost',
    /**
     * Excluding request for live reload and HMR from angular
     */
    context: function (path) {
      return !path.match(/\/ng-cli-ws/);
    },
    onProxyRes: (proxyResponse) => {
      'use strict';

      if (proxyResponse.headers['set-cookie']) {
        const cookies = proxyResponse.headers['set-cookie'].map((cookie) =>
          cookie.replace(/;\s{0,}secure/gi, '')
        );
        proxyResponse.headers['set-cookie'] = cookies;
      }
    }
  }
];
