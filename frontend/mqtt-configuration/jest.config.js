// jest.config.js
module.exports = {
  preset: 'jest-preset-angular',
  setupFilesAfterEnv: ['<rootDir>/setup-jest.js'],
  transformIgnorePatterns: ['/!node_modules\\/lodash-es/']
};
