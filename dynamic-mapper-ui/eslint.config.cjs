const tsParser = require('@typescript-eslint/parser');
const tsPlugin = require('@typescript-eslint/eslint-plugin');
const cypressPlugin = require('eslint-plugin-cypress');

module.exports = [
  {
    ignores: ['node_modules/**', 'dist/**', 'coverage/**', '**/*.component.html'],
  },
  {
    files: ['**/*.ts'],
    languageOptions: {
      parser: tsParser,
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module',
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
    },
    rules: {},
  },
  {
    files: ['cypress/**/*.ts'],
    languageOptions: {
      globals: {
        cy: 'readonly',
        Cypress: 'readonly',
        expect: 'readonly',
        assert: 'readonly',
        describe: 'readonly',
        it: 'readonly',
        before: 'readonly',
        beforeEach: 'readonly',
        after: 'readonly',
        afterEach: 'readonly',
      },
    },
    plugins: {
      cypress: cypressPlugin,
    },
    rules: {
      ...tsPlugin.configs.recommended.rules,
      ...cypressPlugin.configs.recommended.rules,
      '@typescript-eslint/no-explicit-any': 'off',
    },
  },
];
