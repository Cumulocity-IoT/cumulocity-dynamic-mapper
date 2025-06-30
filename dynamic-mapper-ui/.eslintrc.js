module.exports = {
  root: true,
  env: {
    browser: true,
    es2021: true
  },
  overrides: [
    {
      files: ['*.ts'],
      parser: '@typescript-eslint/parser',
      parserOptions: {
        ecmaVersion: 'latest',
        sourceType: 'module'
      },
      plugins: ['import', '@typescript-eslint', '@angular-eslint'],
      extends: [
        'eslint:recommended',
        'plugin:@typescript-eslint/recommended',
        'plugin:@angular-eslint/recommended',
        // This is required if you use inline templates in Components
        'plugin:@angular-eslint/template/process-inline-templates'
      ],
      rules: {
        semi: ['error'],
        'spaced-comment': [
          'error',
          'always',
          {
            line: {
              exceptions: ['/']
            },
            markers: ['//////////']
          }
        ],
        'func-names': ['error', 'never'],
        'no-use-before-define': 'off',
        'comma-dangle': [
          'error',
          {
            arrays: 'ignore',
            objects: 'ignore',
            imports: 'ignore',
            exports: 'ignore',
            functions: 'never'
          }
        ],
        strict: ['error', 'function'],
        'no-param-reassign': [
          'error',
          {
            props: false
          }
        ],
        'no-plusplus': 'off',
        'no-redeclare': 'off',
        'no-extra-semi': 'error',
        'no-multi-spaces': 'error',
        'no-trailing-spaces': 'error',
        'no-multiple-empty-lines': 'error',
        'no-underscore-dangle': 0,
        'prefer-template': 'warn',
        'linebreak-style': 'off',
        'object-property-newline': 'off',
        'prefer-destructuring': [
          'error',
          {
            VariableDeclarator: {
              array: true,
              object: true
            },
            AssignmentExpression: {
              array: false,
              object: false
            }
          },
          {
            enforceForRenamedProperties: false
          }
        ],
        'max-len': 'off',
        'arrow-parens': 'off',
        'function-paren-newline': 'off',
        indent: 'off',
        'implicit-arrow-linebreak': 'off',
        'object-curly-newline': [
          'error',
          {
            consistent: true
          }
        ],
        'operator-linebreak': 'off',
        'space-before-function-paren': 'off',
        'no-confusing-arrow': 'off',
        quotes: [
          'error',
          'single',
          {
            avoidEscape: true,
            allowTemplateLiterals: false
          }
        ],
        '@typescript-eslint/no-inferrable-types': 'off',
        '@typescript-eslint/no-explicit-any': 'off',
        '@typescript-eslint/ban-types': [
          'error',
          {
            types: {
              Function: false
            }
          }
        ],
        'no-prototype-builtins': 'warn',
        '@angular-eslint/directive-selector': [
          'error',
          { type: 'attribute', prefix: ['c8y', 'd11r'], style: 'camelCase' }
        ],
        '@angular-eslint/component-selector': [
          'error',
          { type: 'element', prefix: ['c8y', 'd11r'], style: 'kebab-case' }
        ],
        'import/no-unused-modules': 'error',
        '@angular-eslint/component-class-suffix': 'off',
      }
    },
    {
      files: ['*.component.html'],
      plugins: ['@angular-eslint/template'],
      extends: ['plugin:@angular-eslint/template/recommended'],
      parser: '@angular-eslint/template-parser',
      parserOptions: {
        project: './tsconfig.json',
        ecmaVersion: 'latest',
        sourceType: 'module'
      },
      rules: {}
    }
  ]
};
