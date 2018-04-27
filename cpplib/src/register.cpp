//
// Created by Benjamin Piwowarski on 14/01/2017.
//

#include <fstream>

#include <xpm/common.hpp>
#include <xpm/filesystem.hpp>
#include <xpm/register.hpp>
#include <yaml-cpp/yaml.h>

#include "private.hpp"

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
  addType(ArrayType);
  addType(AnyType);
}
Register::~Register() {}

void Register::addType(std::shared_ptr<Type> const &type) {
  _types[type->typeName()] = type;
}

void Register::addTask(std::shared_ptr<Task> const &task) {
  _tasks[task->identifier()] = task;
}

std::shared_ptr<Task> Register::getTask(TypeName const &typeName, bool allowPlaceholder) {
  auto it = _tasks.find(typeName);
  if (it != _tasks.end()) {
    return it->second;
  }
  return nullptr;
}

std::shared_ptr<Type> Register::getType(TypeName const &typeName) {
  auto it = _types.find(typeName);
  if (it != _types.end()) {
    return it->second;
  }
  return nullptr;
}

// Find a type given a type name
std::shared_ptr<Type> Register::getType(std::shared_ptr<StructuredValue> const &object) {
  return object->type();
}

void Register::parse(std::vector<std::string> const &args) {
  if (args.size() < 1) {
    throw argument_error("Expected at least one argument (use help to get some help)");
  }

  if (args[0] == "help") {
    std::cerr << "[Commands]\n";
    std::cerr << "   help\tGet some help" << std::endl;
    std::cerr << "   generate\tGenerate definitions in JSON format" << std::endl;
    std::cerr << "   run" << std::endl;

    std::cerr << std::endl;

    std::cerr << "[available tasks]\n";
    for (auto &entry: _tasks) {
      std::cerr << "   " << entry.first << std::endl;
    }
    std::cerr << std::endl;
    return;
  }

  if (args[0] == "generate") {
    generate();
    return;
  }

  if (args[0] == "run") {
    // Retrieve the task
    std::string taskName = args[1];
    auto task = this->getTask(TypeName(taskName));
    if (!task) {
      throw argument_error(taskName + " is not a task");

    }

    // Retrieve the structured value
    // TODO:
    // - process other arguments (SV)
    // - process command lines
    std::ifstream stream(args[2]);
    if (!stream) {
      throw argument_error(args[2] + " is not a file");
    }

    json j;
    try {
      j = json::parse(stream);
    } catch (...) {
      LOGGER->error("Error while parsing " + args[2]);
      throw;
    }

    auto sv = std::make_shared<StructuredValue>(*this, j);

    // TODO: check if needed
    // auto value = task->create(_defaultObjectFactory);
    // value->fill(*this, j);

    // Parse further command line options
    // size_t ix = 3;
    // while (ix < args.size()) {
    //   std::string const &arg = args[ix];
    //   auto p_equals = arg.find("=");
    // }

    // Run the task
    progress(-1);
    sv->createObjects(*this);
    runTask(task, sv);
    return;
  }

  throw argument_error("Unexpected command: " + args[0]);
}

void Register::runTask(std::shared_ptr<Task> const & task, std::shared_ptr<StructuredValue> const & sv) {

}

  /// Create object
std::shared_ptr<Object> Register::createObject(std::shared_ptr<StructuredValue> const & sv) {
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

std::shared_ptr<StructuredValue> Register::build(std::string const &value) {
  return std::make_shared<StructuredValue>(*this, json::parse(value));
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
      _types[typeName] = type = std::make_shared<Type>(typeName);
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
        auto parentType = std::make_shared<Type>(parentTypeName);
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
        auto object = std::make_shared<StructuredValue>(*this, it_prop.value());
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
          a->defaultValue(std::make_shared<StructuredValue>(*this, value["default"]));
        }

        if (value.count("generator")) {
          LOGGER->debug("    -> Found a generator");
          a->generator(Generator::createFromJSON(value["generator"]));
        }
      }

      auto valueType = getType(valueTypename);
      if (!valueType) {
        addType(valueType = std::make_shared<Type>(valueTypename));
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
    CommandLine commandLine;
    commandLine.load(e["command"]);
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
