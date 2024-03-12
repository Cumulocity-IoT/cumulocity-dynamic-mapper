import { defineConfig } from 'cypress';

export default defineConfig({
  e2e: {
   baseUrl: 'http://localhost:4200',
   env: {
    // https://github.com/bahmutov/cypress-slow-down
    commandDelay: 150,
  },
  },
  viewportWidth: 1920,
  viewportHeight: 1080,
  video:true,
  videoCompression: 0,
});
