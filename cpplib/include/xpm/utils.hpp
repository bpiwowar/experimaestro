//
// Created by Benjamin Piwowarski on 08/12/2016.
//

#ifndef PROJECT_UTILS_HPP
#define PROJECT_UTILS_HPP

#include <memory>

#ifndef SWIG
#define SWIG_IGNORE
#define SWIG_REMOVE(x) x
#define XPM_PIMPL(x) x : public Pimpl<x>
#define XPM_PIMPL_CHILD(name, parent) name : public PimplChild<name, parent>
#endif

namespace xpm {

template<typename T>
struct Reference;
template<typename T, typename Parent>
struct ChildReference;
template<typename T, typename Parent>
class PimplChild;

template<typename T, typename Parent>
class PimplChild : public Parent {
 protected:
  template<typename... _Args>
  PimplChild(_Args &&...__args) {
    this->_this = std::make_shared<Reference<T>>(std::forward<_Args>(__args)...);
  }

};

template<typename T>
class Pimpl {
 protected:
  typedef std::shared_ptr<Reference<T>> ThisPtr;
  friend struct Reference<T>;

  template<typename U, typename V>
  friend Reference<U> &self(PimplChild<U, V> *p) {
    return *std::dynamic_pointer_cast<Reference<U>>(p->_this);
  };

  template<typename U>
  friend Reference<U> &self(Pimpl<U> *p) {
    return *p->_this;
  }
  template<typename U, typename V>
  friend Reference<U> const &self(PimplChild<U, V> const *p) {
    return *std::dynamic_pointer_cast<Reference<U>>(p->_this);
  };

  template<typename U>
  friend Reference<U> const &self(Pimpl<U> const *p) {
    return *p->_this;
  }

  template<typename... _Args>
  static inline ThisPtr make_this(_Args &&...__args) {
    return std::make_shared<Reference<T>>(std::forward<_Args>(__args)...);
  }

  ThisPtr _this;

  template<typename... _Args>
  Pimpl(_Args &&...__args) :
      _this(std::make_shared<Reference<T>>(std::forward<_Args>(__args)...)) {
  }

  Pimpl(ThisPtr const &ptr) : _this(ptr) {
  }

};

}
#endif //PROJECT_UTILS_HPP
