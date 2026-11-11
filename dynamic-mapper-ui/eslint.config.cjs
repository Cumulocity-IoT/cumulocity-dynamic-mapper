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
    // Not a dynamic component, but it cannot move either: SelectKeyboardService.register$ (from @c8y/ngx-components)
    // takes the `QueryList` of its list items, which only @ViewChildren produces, and the select is a form control
    // that writes its own `disabled` state. Converting the inputs alone would still leave one decorator to exempt.
    files: ['src/shared/component/select/custom-select.component.ts'],
    rules: {
      '@angular-eslint/prefer-signals': 'off',
    },
  },
  {
    // Components that BottomDrawerService.openDrawer or BsModalService.show create. Both copy `initialState` onto the
    // instance with Object.assign, which cannot write a signal input (the field is readonly), so these keep
    // @Input(). List a component here only if it is opened that way (docs/ui/architecture.md, "State and change
    // detection").
    files: [
      'src/configuration/code-template/shared-code-ai-prompt.component.ts',
      'src/mapping/grid/copy-source-modal/copy-source-modal.component.ts',
      'src/mapping/mapping-create/mapping-type-drawer.component.ts',
      'src/mapping/message-explorer/message-explorer-drawer.component.ts',
      'src/mapping/prompt/ai-prompt.component.ts',
      'src/mapping/validation/mapping-validation-drawer.component.ts',
      'src/mapping/versions/mapping-version-drawer.component.ts',
      'src/mapping/versions/publish-version-modal.component.ts',
      'src/shared/component/code-explorer/code-editor-drawer.component.ts',
      'src/shared/component/code-template/manage-template.component.ts',
      'src/shared/component/confirmation/confirmation-modal.component.ts',
      'src/shared/connector-configuration/edit/connector-configuration-drawer.component.ts'
    ],
    rules: {
      '@angular-eslint/prefer-signals': 'off',
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
