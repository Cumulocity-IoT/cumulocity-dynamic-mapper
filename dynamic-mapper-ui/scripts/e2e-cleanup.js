#!/usr/bin/env node
/**
 * Cleanup job for the Cypress e2e suite. Removes everything a run can leave behind on the tenant,
 * for example after a crashed or cancelled run whose teardown never executed:
 *
 *   - connectors whose name starts with "Cypress"
 *   - mappings whose name starts with "cypress" (case-insensitive); active ones are deactivated first
 *   - device types starting with "Cypress" in the outbound type subscription (others are kept)
 *   - users whose user name starts with "cypress-"
 *
 * It talks to the tenant directly over REST (no browser, no dev server), so it also runs as a CI
 * step with `if: always()`. Credentials come from dynamic-mapper-ui/.env or the environment:
 * C8Y_BASEURL, C8Y_TENANT, C8Y_USERNAME, C8Y_PASSWORD.
 *
 *   npm run e2e:cleanup               delete leftovers
 *   npm run e2e:cleanup -- --dry-run  only list them
 *
 * Exit code is 1 if any deletion failed, so a CI step surfaces a leak.
 */
const path = require('path');
require('dotenv').config({ path: path.resolve(__dirname, '../.env') });

const DRY_RUN = process.argv.includes('--dry-run');
const SERVICE = '/service/dynamic-mapper-service';

const required = (name) => {
  const value = process.env[`CYPRESS_${name}`] || process.env[name];
  if (!value) {
    console.error(`Missing ${name} (set it in dynamic-mapper-ui/.env or the environment).`);
    process.exit(2);
  }
  return value;
};

const baseUrl = required('C8Y_BASEURL').replace(/\/+$/, '');
const tenant = required('C8Y_TENANT');
const authorization =
  'Basic ' +
  Buffer.from(`${required('C8Y_USERNAME')}:${required('C8Y_PASSWORD')}`).toString('base64');

let failures = 0;

const call = async (method, url, body) => {
  const response = await fetch(`${baseUrl}${url}`, {
    method,
    headers: {
      Authorization: authorization,
      Accept: 'application/json',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await response.text();
  let json;
  try {
    json = text ? JSON.parse(text) : undefined;
  } catch {
    json = undefined;
  }
  return { ok: response.ok, status: response.status, json };
};

/** Delete one resource, or only report it with --dry-run. Counts failures. */
const remove = async (kind, label, perform) => {
  if (DRY_RUN) {
    console.log(`[dry-run] would delete ${kind} ${label}`);
    return;
  }
  const result = await perform();
  if (result.ok || result.status === 404) {
    console.log(`deleted ${kind} ${label}`);
  } else {
    failures++;
    console.error(`FAILED to delete ${kind} ${label}: HTTP ${result.status}`);
  }
};

const asList = (json, ...keys) => {
  if (Array.isArray(json)) return json;
  for (const key of keys) {
    if (Array.isArray(json?.[key])) return json[key];
  }
  return [];
};

const cleanConnectors = async () => {
  const { json } = await call('GET', `${SERVICE}/configuration/connector/instance`);
  const leftovers = asList(json, 'connectors', 'configurations').filter(
    (c) => (c?.name || '').startsWith('Cypress') && c?.identifier
  );
  console.log(`connectors: ${leftovers.length} leftover`);
  for (const c of leftovers) {
    await remove('connector', `${c.name} (${c.identifier})`, () =>
      call('DELETE', `${SERVICE}/configuration/connector/instance/${c.identifier}`)
    );
  }
};

const cleanMappings = async () => {
  const { json } = await call('GET', `${SERVICE}/mapping`);
  const leftovers = asList(json, 'mappings').filter(
    (m) => /^cypress/i.test(m?.name || '') && m?.id
  );
  console.log(`mappings: ${leftovers.length} leftover`);
  for (const m of leftovers) {
    await remove('mapping', `${m.name} (${m.id})`, async () => {
      // An active mapping cannot be deleted. Deactivating an inactive one is harmless.
      await call('POST', `${SERVICE}/operation`, {
        operation: 'ACTIVATE_MAPPING',
        parameter: { id: String(m.id), active: 'false' },
      });
      return call('DELETE', `${SERVICE}/mapping/${m.id}`);
    });
  }
};

const cleanTypeSubscription = async () => {
  const { ok, json } = await call('GET', `${SERVICE}/subscription/type`);
  if (!ok) {
    console.log('type subscription: not available, skipped');
    return;
  }
  const types = asList(json, 'types');
  const kept = types.filter((t) => !String(t).startsWith('Cypress'));
  console.log(`type subscription: ${types.length - kept.length} leftover`);
  if (kept.length !== types.length) {
    await remove('type subscription entries', types.filter((t) => !kept.includes(t)).join(', '), () =>
      call('PUT', `${SERVICE}/subscription/type`, { api: 'ALL', types: kept })
    );
  }
};

const cleanUsers = async () => {
  const { json } = await call('GET', `/user/${tenant}/users?pageSize=2000`);
  const leftovers = asList(json, 'users').filter((u) =>
    (u?.userName || '').startsWith('cypress-')
  );
  console.log(`users: ${leftovers.length} leftover`);
  for (const u of leftovers) {
    await remove('user', u.userName, () =>
      call('DELETE', `/user/${tenant}/users/${encodeURIComponent(u.userName)}`)
    );
  }
};

(async () => {
  console.log(`${DRY_RUN ? 'Dry run against' : 'Cleaning'} ${baseUrl} (tenant ${tenant})`);
  // Mappings first: they reference connectors.
  await cleanMappings();
  await cleanConnectors();
  await cleanTypeSubscription();
  await cleanUsers();
  if (failures > 0) {
    console.error(`${failures} deletion(s) failed.`);
    process.exit(1);
  }
})().catch((error) => {
  console.error(error);
  process.exit(1);
});
