#!/usr/bin/env ruby
require 'fileutils'
require 'psych'

def command_key(command)
  return 'step' unless command.is_a?(Hash) && !command.empty?
  command.keys.first.to_s
end

def screenshot_command?(command)
  command_key(command) == 'takeScreenshot'
end

def instrument_commands(commands, path = [])
  return commands unless commands.is_a?(Array)

  instrumented = []

  commands.each_with_index do |command, index|
    step_path = path + [format('%03d', index + 1)]
    current = command

    if current.is_a?(Hash)
      key = current.keys.first
      value = current[key]

      if key.to_s == 'runFlow' && value.is_a?(Hash) && value['commands'].is_a?(Array)
        nested = value.dup
        nested['commands'] = instrument_commands(value['commands'], step_path)
        current = current.dup
        current[key] = nested
      end
    end

    instrumented << current
    next if screenshot_command?(current)

    instrumented << {
      'takeScreenshot' => "step-#{step_path.join('-')}-#{command_key(current)}"
    }
  end

  instrumented
end

def instrument_file(source_file, target_file)
  docs = Psych.load_stream(File.read(source_file))
  if !docs.empty? && docs.last.is_a?(Array)
    docs[-1] = instrument_commands(docs.last)
  end

  FileUtils.mkdir_p(File.dirname(target_file))
  File.write(target_file, Psych.dump_stream(*docs))
end

def yaml_file?(path)
  File.file?(path) && %w[.yaml .yml].include?(File.extname(path).downcase)
end

source = File.expand_path(ARGV.fetch(0))
output_root = File.expand_path(ARGV.fetch(1))

if File.directory?(source)
  target_root = File.join(output_root, File.basename(source))
  Dir.glob(File.join(source, '**', '*'), File::FNM_DOTMATCH).each do |entry|
    next if ['.', '..'].include?(File.basename(entry))

    relative = entry.delete_prefix("#{source}/")
    target = File.join(target_root, relative)

    if File.directory?(entry)
      FileUtils.mkdir_p(target)
    elsif yaml_file?(entry)
      instrument_file(entry, target)
    else
      FileUtils.mkdir_p(File.dirname(target))
      FileUtils.cp(entry, target)
    end
  end
  puts target_root
elsif yaml_file?(source)
  target_file = File.join(output_root, File.basename(source))
  instrument_file(source, target_file)
  puts target_file
else
  raise "Unsupported Maestro target: #{source}"
end
