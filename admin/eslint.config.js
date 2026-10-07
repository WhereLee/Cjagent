import js from '@eslint/js'
import prettier from 'eslint-config-prettier'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

/**
 * 平面配置（eslint 9）。几条刻意的选择：
 *
 * - `no-undef` 关掉：TypeScript 编译器已经做这件事，留着只会对 window/localStorage
 *   这类浏览器全局误报，是这类项目最常见的"为了过 lint 而写注释"来源。
 * - `--max-warnings 0`（见 package.json 的 lint 脚本）：warning 不挡门禁等于没有规则。
 * - prettier 放最后：它只关排版类规则，不与我们自己的规则抢。
 */
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', '*.timestamp-*'] },
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
      'vue/component-name-in-template-casing': ['error', 'PascalCase'],
      'vue/no-v-html': 'error',
      eqeqeq: ['error', 'always', { null: 'ignore' }],
      'no-console': ['error', { allow: ['warn', 'error'] }],
    },
  },
  prettier,
)
