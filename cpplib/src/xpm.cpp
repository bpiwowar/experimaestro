
#include <sstream>
#include <iostream>
#include <unordered_set>
#include <fstream>
#include <typeinfo>
#include <xpm/common.hpp>
#include <xpm/xpm.hpp>
#include <xpm/register.hpp>
#include <xpm/value.hpp>
#include <xpm/context.hpp>
#include <xpm/rpc/client.hpp>
#include "private.hpp"

DEFINE_LOGGER("xpm")

using nlohmann::json;

/// Format a string
//template<typename ... Args>
//std::string stringFormat(const std::string &format, Args ... args) {
//  size_t size = snprintf(nullptr, 0, format.c_str(), args ...) + 1; // Extra space for '\0'
//  std::unique_ptr<char[]> buf(new char[size]);
//  snprintf(buf.get(), size, format.c_str(), args ...);
//  return std::string(buf.get(), buf.get() + size - 1); // We don't want the '\0' inside
//}


namespace xpm {

/// Type of the object
const std::string KEY_TYPE = "$type";

/// Task that generated it
const std::string KEY_TASK = "$task";

/// Path to the main resource
const std::string KEY_PATH = "$path";

/// Value
const std::string KEY_VALUE = "$value";

/// Fields that should be ignored (beside $...)
const std::string KEY_IGNORE = "$ignore";

/// Default value
const std::string KEY_DEFAULT = "$default";


static const auto RESTRICTED_KEYS = std::unordered_set<std::string> {KEY_TYPE, KEY_TASK, KEY_VALUE, KEY_DEFAULT};

const TypeName STRING_TYPE("string");
const TypeName BOOLEAN_TYPE("boolean");
const TypeName INTEGER_TYPE("integer");
const TypeName REAL_TYPE("real");
const TypeName ARRAY_TYPE("array");
const TypeName ANY_TYPE("any");
const TypeName PATH_TYPE("path");

static const std::unordered_set<TypeName> IGNORED_TYPES = {PATH_TYPE, RESOURCE_TYPE};

sealed_error::sealed_error() : exception("Object is sealed: cannot modify") {}
argument_error::argument_error(const std::string &message) : exception(message) {}
cast_error::cast_error(const std::string &message) : exception(message) {}
not_implemented_error::not_implemented_error(const std::string &message,
                                             const std::string &file, int line) : exception(
    "Not implemented: " + message + ", file " + file + ":" + std::to_string(line)) {}



// ---
// --- Type names
// ---

TypeName::TypeName(std::string const &name) : name(name) {}

std::string TypeName::toString() const {
  return name;
}

int TypeName::hash() const {
  return (int) std::hash<std::string>{}(name);
}

TypeName TypeName::call(std::string const &localname) const {
  return TypeName(name + "." + localname);
}

std::string TypeName::localName() const {
  const auto i = name.rfind(".");
  if (i == std::string::npos) return name;
  return name.substr(i + 1);
}

// ---
// --- Structured value
// ---

Configuration::Configuration() : _flags(0), _type(AnyType) {
}

Configuration::Configuration(std::map<std::string, std::shared_ptr<Configuration>> &map)
    : _flags(0), _content(map) {
}

Configuration::Configuration(Configuration const &other) : _flags(other._flags), _content(other._content) {
}



Configuration::Configuration(Register &xpmRegister, nlohmann::json const &jsonValue) {
  switch (jsonValue.type()) {

    // --- Object
    case nlohmann::json::value_t::object: {
      // (1) Get the type of the object
      std::shared_ptr<Type> type;
      if (jsonValue.count(KEY_TYPE) > 0) {
        auto typeName = TypeName((std::string const &) jsonValue[KEY_TYPE]);
        _type = xpmRegister.getType(typeName);
        if (!_type) {
          _type = std::make_shared<Type>(typeName);
          _type->placeholder(true);
          xpmRegister.addType(_type);
          LOGGER->warn("Could not find type {} in registry: using undefined type", typeName);
        }
      }
      
      // (2) Fill from JSON
      for (json::const_iterator it = jsonValue.begin(); it != jsonValue.end(); ++it) {
        if (it.key() == KEY_VALUE) {
            // Infer type from value
            _value = Value(xpmRegister, it.value());
            if (_type) _value = _value.cast(_type);
        } else if (it.key() == KEY_TYPE) {
          // ignore
        } else if (it.key() == KEY_DEFAULT) {
          if (!it.value().is_boolean())
            throw std::runtime_error("Default flag is not a boolean value in JSON");
          set(Flag::DEFAULT, it.value());
        } else if (it.key() == KEY_TASK) {
          _task = xpmRegister.getTask(it.value(), true);
        } else {
          set(it.key(), std::make_shared<Configuration>(xpmRegister, it.value()));
        }
      }

      LOGGER->debug("Got an object of type {}", _type->toString());
      break;
    }

    default: {
      _value = Value(xpmRegister, jsonValue);
    }
  }
}

// Convert to JSON
json Configuration::toJson() {
  // No content
  if (_content.empty() && !_task && (!_type || dynamic_cast<Value *>(this)) && !get(Flag::DEFAULT)) {
    return nullptr;
  }

  // We have some values
  json o = json::object();
  for (auto const &entry: _content) {
    o[entry.first] = entry.second->toJson();
  }

  if (get(Flag::DEFAULT))
    o[KEY_DEFAULT] = true;
  if (_type) {
    o[KEY_TYPE] = _type->typeName().toString();
  }

  if (_task) {
    o[KEY_TASK] = _task->identifier().toString();
  }

  return o;
}

std::shared_ptr<Configuration> Configuration::copy() {
  return std::make_shared<Configuration>(*this);
}

std::string Configuration::toJsonString() {
  return toJson().dump();
}

void Configuration::setValue(std::shared_ptr<Configuration> const &value) {
  NOT_IMPLEMENTED();
}

/// Internal digest function
std::array<unsigned char, DIGEST_LENGTH> Configuration::digest() const {
  Digest d;

  d.updateDigest("task");
  if (_task) {
    d.updateDigest(_task->identifier().toString());
  } else {
    d.updateDigest(0);
  }

  for (auto &item: _content) {
    auto const &key = item.first;

    if (key[0] == '$' && key != KEY_TYPE && key != KEY_TASK) {
      // Skip all keys begining by "$s" but $type and $task
      continue;
    }

    if (item.second->canIgnore()) {
      // Remove keys that can be ignored (e.g. paths)
      continue;
    }

    // Update digest with key
    d.updateDigest(key);

    // Update digest with *value digest* (this allows digest caching)
    d.updateDigest(item.second->digest());
  }

  return d.get();
};

std::shared_ptr<Type> Configuration::type() {
  if (_type) return _type;
  return AnyType;
}

bool Configuration::hasKey(std::string const &key) const {
  return _content.find(key) != _content.end();
}

std::shared_ptr<Configuration> Configuration::set(const std::string &key, std::shared_ptr<Configuration> const &value) {
  if (get(Flag::SEALED)) {
    throw sealed_error();
  }

  if (RESTRICTED_KEYS.count(key) > 0)
    throw argument_error("Cannot access directly to " + key);

  auto it = _content.find(key);
  _content[key] = value;

  // And for the object
  setValue(key, value);

  return it == _content.end() ? nullptr : it->second;
}

std::shared_ptr<Configuration> Configuration::get(const std::string &key) {
  auto value = _content.find(key);
  if (value == _content.end()) throw std::out_of_range(key + " is not defined for object");
  return value->second;
}

void Configuration::seal() {
  if (get(Flag::SEALED)) return;

  for (auto &item: _content) {
    item.second->seal();
  }

  set(Flag::SEALED, true);
}

bool Configuration::isSealed() const {
  return get(Flag::SEALED);
}

bool Configuration::isDefault() const {
  return get(Flag::DEFAULT);
}

bool Configuration::ignore() const {
  return get(Flag::IGNORE);
}

bool Configuration::canIgnore() {
  // If the ignore flag is set
  if (ignore()) {
    return true;
  }

  // If the type is ignorable
  if (type()->canIgnore()) {
    return true;
  }

  // Is the ignore flag set?
  auto it = _content.find(KEY_IGNORE);
  if (it != _content.end()) {
    return it->second->_value.asBoolean();
  }

  // Is the value a default value?
  if (isDefault())
    return true;

  return false;
}

std::string Configuration::uniqueIdentifier() const {
  // Compute the digest
  auto array = digest();

  // Transform into hexadecimal string
  std::string s;
  s.reserve(2 * array.size());

  std::array<char, 3> b;
  for (size_t i = 0; i < array.size(); ++i) {
    sprintf(b.data(), "%02x", array[i]);
    s += b.data();
  }

  return s;
}

std::map<std::string, std::shared_ptr<Configuration>> const &Configuration::content() {
  return _content;
}

/** Get type */
void Configuration::type(std::shared_ptr<Type> const &type) {
  _type = type;
}

void Configuration::task(std::shared_ptr<Task> const &task) {
  _task = task;
}

std::shared_ptr<Task> Configuration::task() {
  return _task;
}

void Configuration::submit(bool send,
                    std::shared_ptr<rpc::Launcher> const &launcher,
                    std::shared_ptr<rpc::LauncherParameters> const &launcherParameters) {
  if (!_task) {
    throw exception("No underlying task for this object: cannot run");
  }
  return _task->submit(shared_from_this(), send, launcher, launcherParameters);
}

void Configuration::configure() {
  GeneratorContext context;
  generate(context);
  validate();
  seal();
}

void Configuration::findDependencies(std::vector<std::shared_ptr<rpc::Dependency>> &dependencies,  bool skipThis) {
  // Stop here
  if (canIgnore())
    return;

  if (!_resource.empty()) {
    LOGGER->info("Found dependency {}", _resource);
    dependencies.push_back(std::make_shared<rpc::ReadWriteDependency>(_resource));
  } else {
    for (auto &entry: _content) {
      entry.second->findDependencies(dependencies, false);
    }
  }
}

bool Configuration::equals(Configuration const &other) const {
  // TODO: implement
  NOT_IMPLEMENTED();
}

void Configuration::generate(GeneratorContext & context) {
  if (auto A = context.enter(this)) {
    // Check if we can modify this object
    if (isSealed()) {
      throw new exception("Cannot generate values within a sealed object");
    }

    // Already generated
    if (get(Flag::GENERATED)) return;

    // (2) Generate values
    if (get(Flag::GENERATED)) {
      LOGGER->debug("Object already generated");
    } else {
      // (3) Add resource
      if (_task) {
        auto identifier = _task->getPathGenerator()->generate(context)->asString();
        LOGGER->info("Setting resource to {}", identifier);
        set(KEY_RESOURCE, identifier);
      }

      LOGGER->debug("Generating values...");
      for (auto type = _type; type; type = type->parentType()) {
        for (auto entry: type->arguments()) {
          Argument &argument = *entry.second;
          auto generator = argument.generator();

          if (!hasKey(argument.name()) && generator) {
            auto generated = generator->generate(context);
            LOGGER->debug("Generating value for {}", argument.name());
            set(argument.name(), generated);
          }
        }
      }
      set(Flag::GENERATED, true);
    }
  }
}

void Configuration::

void Configuration::validate() {
  if (get(Flag::VALIDATED)) return;

  if (!get(Flag::SEALED)) set(Flag::VALIDATED, false);

  // Loop over the whole hierarchy
  for (auto type = _type; type; type = type->parentType()) {
    LOGGER->debug("Looking at type {} [{} arguments]", type->typeName(), type->arguments().size());

    // Loop over all the arguments
    for (auto entry: type->arguments()) {
      auto &argument = *entry.second;
      LOGGER->debug("Looking at argument {}", argument.name());

      if (_content.count(argument.name()) == 0) {
        LOGGER->debug("No value provided...");
        // No value provided, and no generator
        if (argument.required() && !(generate && argument.generator())) {
          throw argument_error(
              "Argument " + argument.name() + " was required but not given for " + this->type()->toString());
        } else {
          if (argument.defaultValue()) {
            LOGGER->debug("Setting default value for {}...", argument.name());
            auto value = argument.defaultValue()->copy();
            value->set(Flag::DEFAULT, true);
            value->set(Flag::IGNORE, argument.ignore());
            set(argument.name(), value);
          } else if (!argument.required()) {
            // Set value null
            setValue(argument.name(), nullptr);
          }
        }
      } else {
        // Sets the value
        auto value = get(argument.name());
        LOGGER->debug("Checking value of {} [type {} vs {}]...", argument.name(), *argument.type(), *value->type());

        // If the value is default, add a flag
        if (argument.defaultValue() && argument.defaultValue()->equals(*value)) {
          LOGGER->debug("Value is default");
          value->set(Flag::DEFAULT, true);
        } else {
          // Check the type

          if (!entry.second->type()->accepts(value->type())) {
            throw argument_error(
                "Argument " + argument.name() + " type is  " + value->type()->toString() 
                + ", but requested type was " + entry.second->type()->toString());
          }

          // If the value has a type, handles this
          if (value->hasKey(KEY_TYPE) && !std::dynamic_pointer_cast<Value>(value)) {
            // Create an object of the key type
            auto v = value->get(KEY_TYPE);
            auto valueType = value->type();
            if (valueType) {
              auto object = valueType->create(nullptr);
              LOGGER->debug("Looking at {}", entry.first);
              object->setValue(value);
              object->validate();
            }
          }
        }

        LOGGER->debug("Validating {}...", argument.name());
        value->validate();
        LOGGER->debug("Setting {}...", argument.name());
        setValue(argument.name(), value);
      }
    }
  }
  set(Flag::VALIDATED, true);
}

void Configuration::execute() {
  // TODO: should display the host language class name
  throw exception("No execute method provided in " + std::string(typeid(*this).name()));
}

void Configuration::pre_execute() {}
void Configuration::post_execute() {}

void Configuration::_pre_execute() {
  // Loop over all the type hierarchy
  for (auto type = _type; type; type = type->parentType()) {
    // Loop over all the arguments
    for (auto entry: type->arguments()) {
      auto value = _content.find(entry.second->name());
      if (value != _content.end()) {
        value->second->_pre_execute();
      }
    }
  }

  this->pre_execute();
}

void Configuration::_post_execute() {
  // Loop over all the type hierarchy
  for (auto type = _type; type; type = type->parentType()) {
    // Loop over all the arguments
    for (auto entry: type->arguments()) {
      auto value = _content.find(entry.second->name());
      if (value != _content.end()) {
        value->second->_post_execute();
      }
    }
  }
  this->post_execute();
}

void Configuration::set(Configuration::Flag flag, bool value) {
  if (value) _flags |= (Flags)flag;
  else _flags &= ~((Flags)flag);

  assert(get(flag) == value);
}

bool Configuration::get(Configuration::Flag flag) const {
  return (Flags)flag & _flags;
}

// ---
// --- Task
// ---


Argument::Argument(std::string const &name) : _name(name), _required(true), _generator(nullptr) {
}

Argument::Argument() : Argument("") {
}

std::string const &Argument::name() const {
  return _name;
}
Argument &Argument::name(std::string const &name) {
  _name = name;
  return *this;
}

bool Argument::required() const { return _required; }

Argument &Argument::required(bool required) {
  _required = required;
  return *this;
}

bool Argument::ignore() const { return _ignore; }

Argument &Argument::ignore(bool ignore) {
  _ignore = ignore;
  return *this;
}


const std::string &Argument::help() const {
  return _help;
}
Argument &Argument::help(const std::string &help) {
  _help = help;
  return *this;
}

Argument &Argument::defaultValue(std::shared_ptr<Configuration> const &defaultValue) {
  _defaultValue = defaultValue;
  _required = false;
  return *this;
}
std::shared_ptr<Configuration> Argument::defaultValue() const { return _defaultValue; }

std::shared_ptr<Generator> Argument::generator() { return _generator; }
std::shared_ptr<Generator> const &Argument::generator() const { return _generator; }
Argument &Argument::generator(std::shared_ptr<Generator> const &generator) {
  _generator = generator;
  return *this;
}

std::shared_ptr<Type> const &Argument::type() const { return _type; }
Argument &Argument::type(std::shared_ptr<Type> const &type) {
  _type = type;
  return *this;
}


// ---- Type

std::shared_ptr<Type> BooleanType = std::make_shared<SimpleType>(BOOLEAN_TYPE, ValueType::BOOLEAN);
std::shared_ptr<Type> IntegerType = std::make_shared<SimpleType>(INTEGER_TYPE, ValueType::INTEGER);
std::shared_ptr<Type> RealType = std::make_shared<SimpleType>(REAL_TYPE, ValueType::REAL);
std::shared_ptr<Type> StringType = std::make_shared<SimpleType>(STRING_TYPE, ValueType::STRING);
std::shared_ptr<Type> PathType = std::make_shared<SimpleType>(PATH_TYPE, ValueType::PATH, true);
std::shared_ptr<Type> ArrayType = std::make_shared<Type>(ARRAY_TYPE, nullptr, true, false, true);

std::shared_ptr<Type> AnyType = std::make_shared<Type>(ANY_TYPE, nullptr, true);

/** Creates an object with a given type */
std::shared_ptr<Configuration> Type::create(std::shared_ptr<ObjectFactory> const &defaultFactory) {
  LOGGER->debug("Creating object from type {} with {}", _type, _factory ? "a factory" : "default factory");
  const std::shared_ptr<Configuration> object = _factory ? _factory->create() : defaultFactory->create();
  object->type(shared_from_this());
  return object;
}

Type::Type(TypeName const &type, std::shared_ptr<Type> parent, bool predefined, bool canIgnore, bool isArray) :
    _type(type), _parent(parent), _predefined(predefined), _canIgnore(canIgnore), _isArray(isArray) {}

Type::~Type() {}

void Type::objectFactory(std::shared_ptr<ObjectFactory> const &factory) {
  _factory = factory;
}

std::shared_ptr<ObjectFactory> const &Type::objectFactory() {
  return _factory;
}

void Type::addArgument(std::shared_ptr<Argument> const &argument) {
  _arguments[argument->name()] = argument;
}

std::unordered_map<std::string, std::shared_ptr<Argument>> &Type::arguments() {
  return _arguments;
}

std::unordered_map<std::string, std::shared_ptr<Argument>> const &Type::arguments() const {
  return _arguments;
}

void Type::parentType(Ptr const &type) {
  _parent = type;
}

Type::Ptr Type::parentType() {
  return _parent;
}

TypeName const &Type::typeName() const { return _type; }

/// Return the type
std::string Type::toString() const { return "type(" + _type.toString() + ")"; }

/// Predefined types
bool Type::predefined() const { return _predefined; }

bool Type::isArray() const { return _isArray; }

std::string Type::toJson() const {
  json response = json::object();

  json jsonArguments = json::object();
  for (auto const &entry: _arguments) {
    auto const &arg = *entry.second;
    json definition = json::object();

    if (!arg.help().empty()) {
      definition["help"] = arg.help();
    }

    // Only output not required when needed
    if (!arg.required() && !arg.defaultValue()) {
      definition["required"] = false;
    }

    if (arg.generator()) {
      definition["generator"] = std::const_pointer_cast<Generator>(arg.generator())->toJson();
    }

    if (arg.defaultValue()) {
      definition["default"] = arg.defaultValue()->toJson();
    }

    if (definition.empty()) {
      definition = arg.type()->typeName().toString();
    } else {
      definition["type"] = arg.type()->typeName().toString();
    }
    jsonArguments[entry.first] = definition;
  }

  if (!jsonArguments.empty()) {
    response["arguments"] = std::move(jsonArguments);
  }

  if (!_properties.empty()) {
    json jsonProperties = json::object();
    for (auto const &entry: _properties) {
      jsonProperties[entry.first] = entry.second->toJson();
    }
    response["properties"] = std::move(jsonProperties);
  }

  if (!_description.empty()) {
    response["description"] = _description;
  }

  if (_parent) {
    response["parent"] = _parent->_type.toString();
  }
  return response.dump();
}
int Type::hash() const {
  return std::hash<Type>()(*this);
}


void Type::setProperty(std::string const &name, Configuration::Ptr const &value) {
  _properties[name] = value;
}

Configuration::Ptr Type::getProperty(std::string const &name) {
  auto it = _properties.find(name);
  if (it == _properties.end()) return nullptr;
  return it->second;
}


bool Type::accepts(Type::Ptr const &other) const {
  
  // Go up
  for(auto current = other; current; current = current->_parent) {
    if (current->_type == _type) return true;
  }

  return false;

}


// ---- Generators

GeneratorLock::GeneratorLock(GeneratorContext * context, Configuration *configuration) : context(context) {
  context->stack.push_back(configuration);
}

const std::string PathGenerator::TYPE = "path";

std::shared_ptr<Generator> Generator::createFromJSON(nlohmann::json const &j) {
  std::string type = j["type"];
  if (type == PathGenerator::TYPE) {
    return std::make_shared<PathGenerator>(j);
  }

  throw std::invalid_argument("Generator type " + type + " not recognized");
}

PathGenerator::PathGenerator(nlohmann::json const &j) : _name((std::string const &)j["name"]) {
}

nlohmann::json PathGenerator::toJson() const {
  return {
      { "type", PathGenerator::TYPE },
      { "name", _name }
  };
}

std::shared_ptr<Configuration> PathGenerator::generate(GeneratorContext const &context) {
  Path p = Context::current().workdir();
  auto uuid = context.stack[0]->uniqueIdentifier();

  if (std::shared_ptr<Task> task = context.stack[0]->task()) {
    p = Path(p, {task->identifier().toString()});
  }

  p = Path(p, {uuid});

  if (!_name.empty()) {
    p = Path(p, { _name });
  }
  return std::make_shared<Value>(p);
}

PathGenerator::PathGenerator(std::string const &name) : _name(name) {

}

// ---- REGISTER

std::shared_ptr<Configuration> ObjectFactory::create() {
  auto object = _create();
  object->_register = _register;
  return object;
}

ObjectFactory::ObjectFactory(std::shared_ptr<Register> const &theRegister) : _register(theRegister) {

}
} // xpm namespace
