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
      // Migration to OnPush and signals (docs/ui/onpush-signals-migration-plan.md). OnPush is an error for the
      // whole tree; the two rules below still count what is left and are promoted to errors per directory
      // once they reach zero.
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
      '@angular-eslint/prefer-signals': 'warn',
      '@typescript-eslint/no-explicit-any': 'warn',
      // A forced render or ngDoCheck hides state that does not notify its view. Use a signal or markForCheck().
      // The two allowed forced renders carry an eslint-disable comment that explains why.
      'no-restricted-syntax': [
        'error',
        {
          selector: "CallExpression[callee.property.name='detectChanges'][callee.object.property.name='cdr']",
          message: 'Do not force a render with cdr.detectChanges(). Use a signal or markForCheck() (docs/ui/onpush-signals-migration-plan.md).'
        },
        {
          selector: "MethodDefinition[key.name='ngDoCheck']",
          message: 'Do not use ngDoCheck. Use a signal, a computed value, or ngOnChanges (docs/ui/onpush-signals-migration-plan.md).'
        }
      ],
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
    // Directories that are fully migrated. Every component in them is OnPush; keep it that way.
    // (Docs: docs/ui/onpush-signals-migration-plan.md.) Promote more directories as they reach zero.
    files: [
      'src/mapping/step-subscription/**/*.ts',
      'src/mapping/step-connector/**/*.ts',
      'src/mapping/step-template/**/*.ts',
      'src/mapping/step-properties/**/*.ts',
      'src/mapping/step-transformation/**/*.ts',
      'src/mapping/step-testing/**/*.ts',
      'src/mapping/substitution/**/*.ts',
      'src/mapping/unified-editor/**/*.ts',
      'src/mapping/editor-base/**/*.ts',
      'src/mapping/subscription/subscription-dynamic-group/**/*.ts',
      'src/mapping/subscription/subscription-static-table/**/*.ts',
      'src/mapping/subscription/subscription-static-tree/**/*.ts',
    ],
    ignores: ['**/*.spec.ts'],
    rules: {
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
    },
  },
  {
    // Directories with no explicit `any` left. A new one is an error here.
    files: [
      'src/mapping/step-subscription/**/*.ts',
      'src/mapping/step-connector/**/*.ts',
      'src/mapping/step-template/**/*.ts',
      'src/mapping/substitution/**/*.ts',
      'src/mapping/unified-editor/**/*.ts',
    ],
    ignores: ['**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
    },
  },
  {
    // Tests deliberately exercise deprecated transformation/mapping types to cover the
    // legacy paths, so suppressing each usage individually would only add noise.
    files: ['**/*.spec.ts'],
    rules: {
      '@typescript-eslint/no-deprecated': 'off',
      // Specs use throwaway components, spies, and loose test doubles; the migration counts app code only.
      '@angular-eslint/prefer-on-push-component-change-detection': 'off',
      '@angular-eslint/prefer-signals': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      'no-restricted-syntax': 'off',
      // specs declare throwaway host components with test selectors
      '@angular-eslint/component-selector': 'off',
    },
  },
  {
    // Generated from the backend; their shape is not ours to type by hand.
    files: ['**/*.generated.ts'],
    rules: {
      '@typescript-eslint/no-explicit-any': 'off',
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
