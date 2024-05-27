/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["src/main/resources/**/*.html"],
  theme: {
    extend: {
      fontFamily: {
        sans: ["Rubik", "sans-serif"],
      },
    },
  },
  plugins: [require("tailwindcss-3d")],
};
