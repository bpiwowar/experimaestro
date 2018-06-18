//
// Created by Benjamin Piwowarski on 14/01/2017.
//

#include <fstream>

#include <xpm/common.hpp>
#include <xpm/filesystem.hpp>
#include <xpm/register.hpp>
#include <yaml-cpp/yaml.h>
#include <__xpm/CLI11.hpp>
#include <__xpm/common.hpp>

namespace xpm {

DEFINE_LOGGER("xpm");

using nlohmann::json;

namespace {
nlohmann::json toJSON(YAML::Node const &node) {
  nlohmann::json j;
  switch (node.Type()) {
    case YAML::NodeType::Sequence: {
      j = json::array();
      for (auto const &element: node) {
        j.push_back(toJSON(element));
      }
    }
      break;

    case YAML::NodeType::Map: {
      j = json::object();
      for (auto const &pair: node) {
        j[pair.first.as<std::string>()] = toJSON(pair.second);
      }
    }
      break;

    case YAML::NodeType::Scalar: {
      const auto s = node.Scalar();
      if (node.Tag() == "!") {
        // A string
        j = s;
      } else if (node.Tag() == "?"){
        // determine which type using json parser
        try {
          j = nlohmann::json::parse(s);
          LOGGER->debug("[yaml -> json] Converted {} in {}", s, j.dump());
        } catch(std::exception &e) {
          LOGGER->debug("[yaml -> json] Could not convert {}", s);
          j = s;
        }
      } else {
        LOGGER->error("[yaml -> json] Unhandled YAML type {}: converting to string", node.Tag());
        j = nlohmann::json::parse(s);
      }
    }
      break;

    default:
    std::cerr << "Unhandled type: " << node.Type() << std::endl;
  }

  return j;
}

}

Register::Register() {
  addType(IntegerType);
  addType(RealType);
  addType(StringType);
  addType(BooleanType);
  addType(PathType);
  addType(AnyType);
}
Register::~Register() {}

void Register::addType(ptr<Type> const &type) {
  _types[type->typeName()] = type;
}

void Register::addTask(ptr<Task> const &task) {
  _tasks[task->identifier()] = task;
}

ptr<Task> Register::getTask(TypeName const &typeName, bool allowPlaceholder) {
  auto it = _tasks.find(typeName);
  if (it != _tasks.end()) {
    return it->second;
  }
  return nullptr;
}

ptr<Type> Register::getType(TypeName const &typeName) {
  std::string _name = typeName.toString();
  if (_name.size() > 2 && _name.substr(_name.size()-2) == "[]") {
    TypeName cType(_name.substr(0, _name.size() - 2));
    auto type = getType(cType);
    return type ? mkptr<ArrayType>(type) : nullptr;
  }

  auto it = _types.find(typeName);
  if (it != _types.end()) {
    return it->second;
  }
  return nullptr;
}

// Find a type given a type name
ptr<Type> Register::getType(ptr<Parameters> const &object) {
  return object->type();
}

std::vector<std::string> reverse(std::vector<std::string> const &_args) {  
  std::vector<std::string> args;
  for(size_t i = _args.size(); i > 0; --i) {
    args.push_back(_args[i-1]);
  }
  return args;
}

namespace {
void showArguments(ptr<Parameters> const & sv, Type const & type, std::string const & indent = "") {
  for(auto const &x: type.arguments()) {
    auto subtype =  x.second->type();
    auto subSV = sv && sv->hasKey(x.first) ? sv->get(x.first) : nullptr;

    if (subSV) {
      auto svType = subSV->type();
      if (!svType) throw assertion_error("internal error: type is not defined in showArguments");
      if (!subtype->accepts(svType)) {
        LOGGER->warn("For {}, type {} does not match requested type {}", x.first, svType->typeName().toString(), subtype->typeName().toString());
      } else {
        subtype = svType;
      }

    }

    auto subtypeName = subtype->typeName().toString();
    std::cout << indent << (indent.empty() ? "" : ".")  << x.first 
      << " (" << subtypeName << ")\t" 
      << x.second->help();
    if (x.second->required()) std::cout << " REQUIRED";
    if (subtype->predefined()) {
      if (subSV && subSV->hasValue()) {
        std::cout << " (value " << subSV->valueAsJson() << ")";
      } else if (x.second->defaultValue()) {
        std::cout << " (default " << x.second->defaultValue()->valueAsJson() << ")";
      }
      std::cout << std::endl;
    } else {
      std::cout << std::endl;
      showArguments(subSV, *subtype, (indent.empty() ? "" : indent + ".") + x.first);
    }
  }
}

/// Follow
ptr<Parameters> getSubValue(ptr<Parameters> sv, std::string const & fullkey, std::string const & separator, bool lenient = false) {
  ptr<Parameters> subsv = nullptr;
  for (size_t pos = 0, next = 0; next != std::string::npos; pos = next + 1) {
    next = fullkey.find(separator, pos+1);
    std::string key = fullkey.substr(pos, next-pos);

    if (sv->hasKey(key)) {
      subsv = sv->get(key);
    } else {
      // Create structured value
      subsv = mkptr<Parameters>();

      // Propagate types
      auto const &arguments = sv->type()->arguments();
      auto it = arguments.find(key);
      if (it != arguments.end()) {
        subsv->type(it->second->type());
      } else if (!lenient) {
        throw argument_error("Cannot find " + fullkey);
      }
      sv->set(key, subsv);
    }
    sv = subsv;
  }

  return subsv;
}

/// Merge values from YAML into a structured value
void merge(Register & xpmRegister, ptr<Parameters> const &sv, YAML::Node const &node) {
  switch (node.Type()) {

  case YAML::NodeType::Sequence:
  case YAML::NodeType::Scalar:
  case YAML::NodeType::Null: {
    sv->set(node);
    break;
  }

  case YAML::NodeType::Map: {
    // Set the type if specified
    if (node.Tag()[0] == '!' && node.Tag().size() > 1) {
      auto typeName = TypeName(node.Tag().substr(1));
      auto type = xpmRegister.getType(typeName);
      if (!type) {
        LOGGER->warn("Undefined type {}", node.Tag());
        type = mkptr<Type>(typeName);
        type->placeholder(true);
        xpmRegister.addType(type);
      }
      sv->type(type);
    }

    // Merge all entries
    for (auto const &pair : node) {
      auto fullkey = pair.first.as<std::string>();

      // Find the structured value
      auto subsv = getSubValue(sv, fullkey, ".");
      merge(xpmRegister, subsv, pair.second); 

    }
    break;
  }

  default: throw argument_error("Cannot convert YAML to Value");
  }
}
} // namespace

bool Register::parse(std::vector<std::string> const &_args, bool tryParse) {
  
  std::vector<std::string> args = reverse(_args);

  CLI::App app{"Experimaestro command line parser"};
  app.require_subcommand(1);
  app.fallthrough(false);

  auto _generate = app.add_subcommand("generate", "Generate definitions in JSON format");
  _generate->set_callback( [&](){
    generate();
  });

  std::set<std::string> taskNames;
  {
    auto _run = app.add_subcommand("run", "Run a given task");
    _run->allow_extras(true);

    std::string paramFile;
    _run->add_option("--json-file", paramFile, "Parameter file in JSON format")
      ->check(CLI::ExistingFile)
      ->required(false);

    std::vector<std::string> yamlStrings;
    _run->add_option("--yaml", yamlStrings, "Parameters in YAML format")
      ->required(false);

    int argumentHelp = 0;
    _run->add_flag("--arguments", argumentHelp, "Get help on task arguments");

    std::string taskName;
    for(auto task: _tasks) {
      taskNames.insert(task.first.toString());
    }
        
    std::string hello;
    _run->add_set("task", taskName, taskNames, "Task name", true)
      ->required();

    std::vector<std::string> parameters;
    _run->add_option("parameters", parameters, "task parameters");

    _run->set_callback( [&](){
      if (parameters.size() % 2 != 0) {
        throw argument_error("Parameters should be of the form [--path-name-of-argument value]*");
      }

      // Retrieve the task
      auto task = this->getTask(TypeName(taskName));
      if (!task) {
        throw argument_error(taskName + " is not a task");
      }

      // Read the JSON file
      ptr<Parameters> sv;
      if (paramFile.empty()) {
        sv = mkptr<Parameters>();
        sv->type(task->type());
      } else {
        std::ifstream stream(paramFile);
    
        json j;
        try {
          j = json::parse(stream);
        } catch (...) {
          LOGGER->error("Error while parsing " + paramFile);
          throw;
        }

        sv = std::make_shared<Parameters>(*this, j);
      }

      // Parse YAML string
      if (!yamlStrings.empty()) {
        for(auto const &yamlString: yamlStrings) {
          auto yaml = YAML::Load(yamlString);
          merge(*this, sv, yaml);
        }
        sv->type(task->type());
      }

      // Set arguments from     
      for(size_t i = 0; i < parameters.size(); i += 2) {
        if (parameters[i].substr(0,2) != "--") {
          throw argument_error("Option '" + parameters[i] + "' does not begin by --");
        }
        auto subsv = getSubValue(sv, parameters[i].substr(2), "-");

        if (subsv->type()->scalar()) {
          subsv->set(parameters[i+1], true);
        } else {
          auto type = getType(parameters[i+1]);
          if (!type) throw argument_error("Type " + parameters[i+1] + " does not exist");
          subsv->type(type);
        }
      }

      LOGGER->info("Structured value: {}", sv->toJson());

      // Handle help flag
      if (argumentHelp) {
        showArguments(sv, *sv->type());
      } else {
        // Run the task
        progress(-1);
        sv->validate();
        sv->createObjects(*this);
        runTask(task, sv);
      }
    });
  }

  try {
    app.parse(const_cast<std::vector<std::string>&>(args));
  } catch(const CLI::ParseError &e) {                                                                                \
    if (tryParse) {
      return false;
    }
    if (app.exit(e)) {
      throw exception();
    }                                                                                         
  }

  return true;
 
}

void Register::runTask(ptr<Task> const & task, ptr<Parameters> const & sv) {
  auto object = sv->object();
  if (!object) {
    throw assertion_error(fmt::format("No object was created for structured value of type {}", sv->type()->toString()));
  }
  
  Task::_running = true;
  finally([] {  
    Task::_running = false;
  });
  object->run();
}

  /// Create object
ptr<Object> Register::createObject(ptr<Parameters> const & sv) {
  return nullptr;
}

void Register::generate() const {
  std::cout << "{";
  std::cout << R"("types": {)" << std::endl;
  bool first = true;
  for (auto const &type: this->_types) {
    if (!type.second->predefined()) {
      if (!first) std::cout << ","; else first = false;
      std::cout << '\"' << type.first.toString() << "\": "
                << type.second->toJson() << std::endl;
    }
  }
  std::cout << "}, " << std::endl;

  std::cout << R"("tasks": {)" << std::endl;
  first = true;
  for (auto const &type: this->_tasks) {
    if (!first) std::cout << ","; else first = false;
    std::cout << '\"' << type.first.toString() << "\": "
              << type.second->toJson() << std::endl;
  }
  std::cout << "}" << std::endl;

  std::cout << "}" << std::endl;
}

ptr<Parameters> Register::build(std::string const &value) {
  return std::make_shared<Parameters>(*this, json::parse(value));
}

void Register::parse(int argc, const char **argv) {
  std::vector<std::string> args;
  for (int i = 1; i < argc; ++i) {
    args.emplace_back(std::string(argv[i]));
  }
  parse(args);
}

void Register::load(const std::string &value) {
  LOGGER->info("Loading XPM register file " + value);
  std::ifstream in(value);
  if (!in) {
    throw std::runtime_error("Register file " + value + " does not exist");
  }
  auto j = json::parse(in);
  load(j);
}


void Register::load(YAML::Node const &node) {
  auto j = toJSON(node);
  load(j);
}

void Register::loadYAML(std::string const &yamlString) {
  load(YAML::Load(yamlString));
}
void Register::loadYAML(Path const &yamlFilepath) {
  LOGGER->info("Loading configuration from YAML {}", yamlFilepath.toString());
  load(YAML::Load(yamlFilepath.getContent()));
}


void Register::load(nlohmann::json const &j) {
  auto types = j["types"];
  assert(types.is_object());

  for (json::iterator it = types.begin(); it != types.end(); ++it) {
    auto const &e = it.value();
    assert(e.is_object());
    const TypeName typeName = TypeName(it.key());
    auto typeIt = _types.find(typeName);
    Type::Ptr type;

    // Search for the type
    if (typeIt != _types.end()) {
      type = typeIt->second;
      LOGGER->debug("Using placeholder type {}", type->typeName().toString());
      if (!type->placeholder()) {
        throw std::runtime_error("Type " + type->typeName().toString() + " was already defined");
      }
      type->placeholder(false);
    } else {
      _types[typeName] = type = mkptr<Type>(typeName);
    }

    if (e.count("description")) {
      type->description(e["description"]);
    }

    // Get the parent type
    if (e.count("parent")) {
      auto parentTypeName = TypeName(e["parent"].get<std::string>());
      auto parentTypeIt = _types.find(parentTypeName);
      if (parentTypeIt == _types.end()) {
        LOGGER->debug("Creating placeholder type {} ", parentTypeName);
        auto parentType = mkptr<Type>(parentTypeName);
        type->parentType(parentType);
        parentType->placeholder(true);
        _types[parentTypeName] = parentType;
      } else {
        type->parentType(parentTypeIt->second);
      }
    }

    LOGGER->debug("Adding type {}", type->typeName());

    if (e.count("properties")) {
      auto properties = e["properties"];
      for (json::iterator it_prop = properties.begin(); it_prop != properties.end(); ++it_prop) {
        auto object = std::make_shared<Parameters>(*this, it_prop.value());
        type->setProperty(it_prop.key(), object);
      }
    }

    // Parse arguments
    auto arguments = e.value("arguments", json::object());
    for (json::iterator it_args = arguments.begin(); it_args != arguments.end(); ++it_args) {
      auto const name = it_args.key();
      auto a = std::make_shared<Argument>(name);
      const auto value = it_args.value();

      std::string valueTypename;
      if (value.is_string()) {
        valueTypename = value.get<std::string>();
      } else {
        if (!value.count("type")) {
          throw argument_error("No defined type for argument " + name + " in definition of type " + typeName.toString());      
        }
        valueTypename = value["type"].get<std::string>();
        a->help(value.value("help", ""));
        a->required(value.value("required", true));

        if (value.count("default")) {
          LOGGER->debug("    -> Found a default value");
          a->defaultValue(std::make_shared<Parameters>(*this, value["default"]));
        }

        if (value.count("generator")) {
          LOGGER->debug("    -> Found a generator");
          a->generator(Generator::createFromJSON(value["generator"]));
        }
      }

      auto valueType = getType(valueTypename);
      if (!valueType) {
        addType(valueType = mkptr<Type>(valueTypename));
        valueType->placeholder(true);
      }
      a->type(valueType);

      LOGGER->debug("   Adding argument {} of type {}", it_args.key(), a->type()->toString());
      type->addArgument(a);
    }

    _types[type->typeName()] = type;
  }

  auto tasks = j["tasks"];
  assert(tasks.is_object());

  for (json::iterator it = tasks.begin(); it != tasks.end(); ++it) {
    auto const &e = it.value();
    TypeName identifier(it.key());
    if (!e.count("type")) {
      throw argument_error("No type for task " + identifier.toString());
    }
    TypeName type(e["type"].get<std::string>());
    auto typePtr = getType(type);

    if (!e.count("command")) {
      throw argument_error("No command for task " + identifier.toString());
    }
    auto commandLine = std::make_shared<CommandLine>();
    commandLine->load(e["command"]);
    auto task = std::make_shared<Task>(identifier, typePtr);
    task->commandline(commandLine);
    addTask(task);
  }
}

void Register::load(Path const &path) {
  std::string content = path.getContent();
  auto j = json::parse(content);
  load(j);
}


}
