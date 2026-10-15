const tsParser = require('@typescript-eslint/parser');
const tsPlugin = require('@typescript-eslint/eslint-plugin');
const cypressPlugin = require('eslint-plugin-cypress');
const angularPlugin = require('@angular-eslint/eslint-plugin');

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
        // no-deprecated is type-aware. Use projectService rather than an explicit
        // `project` path: the root tsconfig.json only holds project references
        // ("files": []), which the explicit form cannot resolve.
        projectService: true,
        tsconfigRootDir: __dirname,
      },
    },
    plugins: {
      '@typescript-eslint': tsPlugin,
      '@angular-eslint': angularPlugin,
    },
    rules: {
      '@typescript-eslint/no-deprecated': 'error',
      // Naming rules
      '@angular-eslint/component-selector': [
        'error',
        { type: 'element', prefix: 'd11r', style: 'kebab-case' },
      ],
      '@angular-eslint/directive-selector': [
        'error',
        { type: 'attribute', prefix: 'd11r', style: 'camelCase' },
      ],
      '@angular-eslint/component-class-suffix': 'error',
      '@angular-eslint/directive-class-suffix': 'error',
      '@typescript-eslint/naming-convention': [
        'error',
        // PascalCase with no all-caps acronyms (AiAgentService, not AIAgentService)
        {
          selector: 'typeLike',
          format: ['StrictPascalCase'],
        },
      ],
    },
  },
  {
    // Tests deliberately exercise deprecated transformation/mapping types to cover the
    // legacy paths, so suppressing each usage individually would only add noise.
    files: ['**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-deprecated': 'off',
      // specs declare throwaway host components with test selectors
      '@angular-eslint/component-selector': 'off',
    },
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
