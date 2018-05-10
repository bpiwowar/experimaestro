//
// Created by Benjamin Piwowarski on 19/11/2016.
//

#include <numeric>
#include <vector>
#include <fstream>
#include <sstream>
#include <streambuf>

#include <spdlog/fmt/fmt.h>

#include <xpm/common.hpp>
#include <xpm/filesystem.hpp>
#include <__xpm/common.hpp>

DEFINE_LOGGER("filesystem");

namespace xpm {

std::ostream &operator<<(std::ostream & out, Path const & path) {
  return out << path.toString();
}

Path::Path(std::string const &path) : Path("", path) {
}

Path::Path() : path("/")  {
}

bool operator==(Path const & lhs, Path const & rhs) {
  return (lhs.share == rhs.share)
    && (lhs.path == rhs.path);
}


Path::Path(Path const &parent, std::initializer_list<std::string> const &relative) :
    Path(parent.share, std::accumulate(relative.begin(), relative.end(), parent.path == "/" ?  "" :  parent.path,
                          [](std::string &s, const std::string &piece) -> std::string { return s += "/" + piece; })) {

}
Path::Path(Path const &parent, std::vector<std::string> const &relative) :
    Path(parent.share, std::accumulate(relative.begin(), relative.end(), parent.path == "/" ?  "" :  parent.path,
                          [](std::string &s, const std::string &piece) -> std::string { return s += "/" + piece; })) {

}

Path::Path(std::string const &share, std::string const &path) : share(share), path(path) {
  // TODO: perform some cleanup
}


Path Path::parent() const {
  unsigned long i = path.rfind('/');
  if (i == 0) {
    return Path(share, "/");
  }
  if (i != std::string::npos) {
    std::string parentpath = path.substr(0, i);
    return Path(share, parentpath);
  }

  return *this;
}



std::string Path::toString() const {
  if (share.empty()) return path;
  return share + ":" + path;
}

std::string Path::name() const {
  auto pos = path.rfind('/');

  return pos == std::string::npos ? path : path.substr(pos + 1);
}

bool Path::isRelative() const {
  return path[0] != '/';
}

std::string Path::localpath() const {
  if (!isLocal())
    throw std::logic_error("Path " + toString() + " is not local");
  return path;
}

bool Path::isLocal() const {
  return share.empty();
}

bool Path::isRoot() const {
  return path == "/";
}

Path Path::relativeTo(Path const & other) const {
  if (isRelative() || other.isRelative()) 
    throw argument_error(fmt::format("Cannot relativize with relative paths {} and {}",
      this->toString(), other.toString()));
  if (share != other.share) return *this;

  std::string const & b = other.path;

  // TODO: normalize paths
  
  // TODO: find common root
  size_t i = 0;
  size_t last_slash = 0;
  while (b[i] == path[i]) {
    if (b[i] == '/') last_slash = i;
    ++i;
  }

  if (b.size() == i) {
    if (path.size() == i) {
      return Path(".");
    }
    if (path[i] == '/') {
      last_slash = i;
    }
  }

  // Add back relative
  std::ostringstream oss;
  auto pos = last_slash;
  while ((pos = b.find('/', pos + 1)) != std::string::npos) {
    oss << "../";
  }
  oss << path.substr(last_slash + 1);

  Path relative(oss.str());
  return relative;
}


std::string Path::getContent() const {
  if (isLocal()) {
    std::ifstream t(localpath());
    std::string str;

    t.seekg(0, std::ios::end);
    str.reserve(t.tellg());
    t.seekg(0, std::ios::beg);

    str.assign((std::istreambuf_iterator<char>(t)),
               std::istreambuf_iterator<char>());
    return str;
  }

  // auto rpcPath = rpc::Path::toPath(toString());
  // return rpcPath->read_all();
  NOT_IMPLEMENTED();
}

}