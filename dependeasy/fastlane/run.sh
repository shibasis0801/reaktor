#!/bin/zsh
set -euo pipefail

: "${BUNDLE_GEMFILE:?Set BUNDLE_GEMFILE to the product's locked Gemfile}"
export FASTLANE_SKIP_UPDATE_CHECK=1
export BUNDLE_FROZEN=true

if command -v bundle >/dev/null 2>&1 && bundle check >/dev/null 2>&1; then
  exec bundle exec fastlane "$@"
fi

for bundle_bin in "$HOME"/.rvm/gems/ruby-*/bin/bundle(N); do
  ruby_version="${bundle_bin#"$HOME"/.rvm/gems/}"
  ruby_version="${ruby_version%%/*}"
  gem_home="$HOME/.rvm/gems/$ruby_version"
  ruby_environment=(PATH="$gem_home/bin:$HOME/.rvm/rubies/$ruby_version/bin:$PATH" GEM_HOME="$gem_home" GEM_PATH="$gem_home:$gem_home@global")
  if env "${ruby_environment[@]}" "$bundle_bin" check >/dev/null 2>&1; then
    exec env "${ruby_environment[@]}" "$bundle_bin" exec fastlane "$@"
  fi
done

print -u2 "The locked Fastlane bundle is unavailable. Run BUNDLE_GEMFILE=$BUNDLE_GEMFILE bundle install with the configured Ruby."
exit 1
