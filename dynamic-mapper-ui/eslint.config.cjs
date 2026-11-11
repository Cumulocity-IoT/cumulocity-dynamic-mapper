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
      // whole tree; the two rules below are errors too; the directories that still have findings are listed
      // as warnings in the "Known debt" blocks further down.
      '@angular-eslint/prefer-on-push-component-change-detection': 'error',
      '@angular-eslint/prefer-signals': 'error',
      '@typescript-eslint/no-explicit-any': 'error',
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
    // Known debt, to be paid down directory by directory (docs/ui/onpush-signals-migration-plan.md). Everything
    // else is an error, so new code and new directories are strict from the start. When a directory reaches
    // zero, delete its line here.
    files: [
      'src/configuration/code-template/*.ts',
      'src/extension/card/*.ts',
      'src/mapping/deprecation-notice/*.ts',
      'src/mapping/editor-base/*.ts',
      'src/mapping/grid/copy-source-modal/*.ts',
      'src/mapping/mapping-create/*.ts',
      'src/mapping/message-explorer/*.ts',
      'src/mapping/prompt/*.ts',
      'src/mapping/step-connector/*.ts',
      'src/mapping/step-properties/*.ts',
      'src/mapping/step-subscription/*.ts',
      'src/mapping/step-template/*.ts',
      'src/mapping/step-testing/*.ts',
      'src/mapping/step-transformation/*.ts',
      'src/mapping/subscription/subscription-dynamic-group/*.ts',
      'src/mapping/subscription/subscription-dynamic-type/*.ts',
      'src/mapping/subscription/subscription-static-table/*.ts',
      'src/mapping/subscription/subscription-static-tree/*.ts',
      'src/mapping/substitution/*.ts',
      'src/mapping/validation/*.ts',
      'src/mapping/versions/*.ts',
      'src/monitoring/cache/kpi/*.ts',
      'src/shared/component/code-explorer/*.ts',
      'src/shared/component/code-template/*.ts',
      'src/shared/component/confirmation/*.ts',
      'src/shared/component/formly/*.ts',
      'src/shared/component/json-editor/*.ts',
      'src/shared/component/select/*.ts',
      'src/shared/connector-configuration/*.ts',
      'src/shared/connector-configuration/edit/*.ts',
      'src/shared/connector-details/*.ts'
    ],
    ignores: ['**/*.spec.ts'],
    rules: {
      '@angular-eslint/prefer-signals': 'warn',
    },
  },
  {
    // Size check: a component class over 500 lines is a candidate for splitting along the seams the OnPush work
    // exposed (docs/ui/onpush-signals-migration-plan.md, "Oversized components"). A warning, not an error: the
    // existing large components are listed in the plan and are split one pull request at a time.
    files: ['src/**/*.component.ts'],
    ignores: ['**/*.spec.ts'],
    rules: {
      'max-lines': ['warn', { max: 500, skipBlankLines: true, skipComments: true }],
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
