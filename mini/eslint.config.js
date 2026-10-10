import js from '@eslint/js'
import prettier from 'eslint-config-prettier'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

/**
 * 规则集与 admin/eslint.config.js 一致（同一类基建不应当一边严一边松）。
 * 两处不同是环境造成的，不是口味：
 *   - 忽略 uni-app 产物目录与生成的 components.d.ts；
 *   - mini 不用 Element Plus，模板里是 uni 内置组件，因此关掉 vue/html-self-closing
 *     之外的排版类规则交给 prettier。
 */
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'src/pages.json', 'src/manifest.json'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    rules: {
      'no-undef': 'off',
      '@typescript-eslint/no-unused-vars': [
        'error',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' },
      ],
      '@typescript-eslint/no-explicit-any': 'error',
      'vue/multi-word-component-names': 'off',
      'vue/component-name-in-template-casing': 'off',
      'vue/no-v-html': 'error',
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      // 小程序端没有 console，且客户在户外报障靠的就是日志；只禁 log，保留 warn/error
      'no-console': ['error', { allow: ['warn', 'error'] }],
    },
  },
  prettier,
  {
    // 构建配置与命令行工具是 Node 环境，console.log 就是它们的输出方式，不该当违规
    files: ['tools/**/*.mjs', 'vite.config.ts'],
    rules: { 'no-console': 'off' },
  },
)
