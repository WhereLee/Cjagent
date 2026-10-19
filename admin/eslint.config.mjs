import pluginVue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import tseslint from 'typescript-eslint'
import prettier from 'eslint-config-prettier'

const rules = {
  'vue/multi-word-component-names': 'off',
  '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_' }],
}

export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'src/api/schema-admin.ts'] },
  ...pluginVue.configs['flat/recommended'],
  ...tseslint.configs.recommended,
  prettier,
  {
    // tseslint 的全局块会把 parser 抢成 TS parser，.vue 必须显式回设 vue-eslint-parser
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue'],
      },
    },
    rules,
  },
  {
    files: ['**/*.ts'],
    languageOptions: {
      parser: tseslint.parser,
    },
    rules,
  },
)
