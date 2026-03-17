//region block: polyfills
if (typeof Math.imul === 'undefined') {
  Math.imul = function imul(a, b) {
    return (a & 4.29490176E9) * (b & 65535) + (a & 65535) * (b | 0) | 0;
  };
}
if (typeof ArrayBuffer.isView === 'undefined') {
  ArrayBuffer.isView = function (a) {
    return a != null && a.__proto__ != null && a.__proto__.__proto__ === Int8Array.prototype.__proto__;
  };
}
if (typeof Array.prototype.fill === 'undefined') {
  // Polyfill from https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Array/fill#Polyfill
  Object.defineProperty(Array.prototype, 'fill', {value: function (value) {
    // Steps 1-2.
    if (this == null) {
      throw new TypeError('this is null or not defined');
    }
    var O = Object(this); // Steps 3-5.
    var len = O.length >>> 0; // Steps 6-7.
    var start = arguments[1];
    var relativeStart = start >> 0; // Step 8.
    var k = relativeStart < 0 ? Math.max(len + relativeStart, 0) : Math.min(relativeStart, len); // Steps 9-10.
    var end = arguments[2];
    var relativeEnd = end === undefined ? len : end >> 0; // Step 11.
    var finalValue = relativeEnd < 0 ? Math.max(len + relativeEnd, 0) : Math.min(relativeEnd, len); // Step 12.
    while (k < finalValue) {
      O[k] = value;
      k++;
    }
    ; // Step 13.
    return O;
  }});
}
[Int8Array, Int16Array, Uint16Array, Int32Array, Float32Array, Float64Array].forEach(function (TypedArray) {
  if (typeof TypedArray.prototype.fill === 'undefined') {
    Object.defineProperty(TypedArray.prototype, 'fill', {value: Array.prototype.fill});
  }
});
if (typeof Math.clz32 === 'undefined') {
  Math.clz32 = function (log, LN2) {
    return function (x) {
      var asUint = x >>> 0;
      if (asUint === 0) {
        return 32;
      }
      return 31 - (log(asUint) / LN2 | 0) | 0; // the "| 0" acts like math.floor
    };
  }(Math.log, Math.LN2);
}
//endregion
//region block: imports
var imul_0 = Math.imul;
var isView = ArrayBuffer.isView;
var clz32 = Math.clz32;
//endregion
//region block: pre-declaration
class CharSequence {}
class Char {}
class Companion {
  fromJsArray_n3u761_k$(array) {
    return createListFrom(array);
  }
}
class Collection {}
class KtList {}
function asJsReadonlyArrayView() {
  return createJsReadonlyArrayViewFrom(this);
}
class Entry {}
class KtMap {}
function asJsReadonlyMapView() {
  return createJsReadonlyMapViewFrom(this);
}
class KtSet {}
class Companion_0 {
  fromJsMap_p3spvk_k$(map) {
    return createMutableMapFrom(map);
  }
}
class KtMutableMap {}
function asJsMapView() {
  return createJsMapViewFrom(this);
}
class Companion_1 {
  fromJsArray_n3u761_k$(array) {
    return createMutableListFrom(array);
  }
}
class MutableIterable {}
class KtMutableList {}
function asJsArrayView() {
  return createJsArrayViewFrom(this);
}
class Companion_2 {}
class Enum {
  constructor(name, ordinal) {
    this.name_1 = name;
    this.ordinal_1 = ordinal;
  }
  compareTo_30rs7w_k$(other) {
    return compareTo(this.ordinal_1, other.ordinal_1);
  }
  compareTo_hpufkf_k$(other) {
    return this.compareTo_30rs7w_k$(other instanceof Enum ? other : THROW_CCE());
  }
  equals(other) {
    return this === other;
  }
  hashCode() {
    return identityHashCode(this);
  }
  toString() {
    return this.name_1;
  }
}
class FunctionAdapter {}
class JsArrayView extends Array {}
class JsMapView extends Map {}
class Comparator {}
class Unit {
  toString() {
    return 'kotlin.Unit';
  }
}
class AbstractCollection {
  static new_kotlin_collections_AbstractCollection_s1tlv0_k$($box) {
    return createThis(this, $box);
  }
  contains_aljjnj_k$(element) {
    var tmp$ret$0;
    $l$block_0: {
      // Inline function 'kotlin.collections.any' call
      var tmp;
      if (isInterface(this, Collection)) {
        tmp = this.isEmpty_y1axqb_k$();
      } else {
        tmp = false;
      }
      if (tmp) {
        tmp$ret$0 = false;
        break $l$block_0;
      }
      var _iterator__ex2g4s = this.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element_0 = _iterator__ex2g4s.next_20eer_k$();
        if (equals(element_0, element)) {
          tmp$ret$0 = true;
          break $l$block_0;
        }
      }
      tmp$ret$0 = false;
    }
    return tmp$ret$0;
  }
  containsAll_bwkf3g_k$(elements) {
    var tmp$ret$0;
    $l$block_0: {
      // Inline function 'kotlin.collections.all' call
      var tmp;
      if (isInterface(elements, Collection)) {
        tmp = elements.isEmpty_y1axqb_k$();
      } else {
        tmp = false;
      }
      if (tmp) {
        tmp$ret$0 = true;
        break $l$block_0;
      }
      var _iterator__ex2g4s = elements.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        if (!this.contains_aljjnj_k$(element)) {
          tmp$ret$0 = false;
          break $l$block_0;
        }
      }
      tmp$ret$0 = true;
    }
    return tmp$ret$0;
  }
  isEmpty_y1axqb_k$() {
    return this.get_size_woubt6_k$() === 0;
  }
  toString() {
    return joinToString_1(this, ', ', '[', ']', VOID, VOID, AbstractCollection$toString$lambda(this));
  }
  toArray() {
    return collectionToArray(this);
  }
}
class AbstractMutableCollection extends AbstractCollection {
  static new_kotlin_collections_AbstractMutableCollection_jgoj1k_k$() {
    return this.new_kotlin_collections_AbstractCollection_s1tlv0_k$();
  }
  remove_cedx0m_k$(element) {
    this.checkIsMutable_jn1ih0_k$();
    var iterator = this.iterator_jk1svi_k$();
    while (iterator.hasNext_bitz1p_k$()) {
      if (equals(iterator.next_20eer_k$(), element)) {
        iterator.remove_ldkf9o_k$();
        return true;
      }
    }
    return false;
  }
  addAll_h3ej1q_k$(elements) {
    this.checkIsMutable_jn1ih0_k$();
    var modified = false;
    var _iterator__ex2g4s = elements.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      if (this.add_utx5q5_k$(element))
        modified = true;
    }
    return modified;
  }
  clear_j9egeb_k$() {
    this.checkIsMutable_jn1ih0_k$();
    var iterator = this.iterator_jk1svi_k$();
    while (iterator.hasNext_bitz1p_k$()) {
      iterator.next_20eer_k$();
      iterator.remove_ldkf9o_k$();
    }
  }
  toJSON() {
    return this.toArray();
  }
  checkIsMutable_jn1ih0_k$() {
  }
}
class IteratorImpl {
  constructor($outer, $box) {
    boxApply(this, $box);
    this.$this_1 = $outer;
    this.index_1 = 0;
    this.last_1 = -1;
  }
  hasNext_bitz1p_k$() {
    return this.index_1 < this.$this_1.get_size_woubt6_k$();
  }
  next_20eer_k$() {
    if (!this.hasNext_bitz1p_k$())
      throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
    var tmp = this;
    var _unary__edvuaz = this.index_1;
    this.index_1 = _unary__edvuaz + 1 | 0;
    tmp.last_1 = _unary__edvuaz;
    return this.$this_1.get_c1px32_k$(this.last_1);
  }
  remove_ldkf9o_k$() {
    // Inline function 'kotlin.check' call
    if (!!(this.last_1 === -1)) {
      var message = 'Call next() or previous() before removing element from the iterator.';
      throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$(toString_1(message));
    }
    this.$this_1.removeAt_6niowx_k$(this.last_1);
    this.index_1 = this.last_1;
    this.last_1 = -1;
  }
}
class ListIteratorImpl extends IteratorImpl {
  constructor($outer, index, $box) {
    if ($box === VOID)
      $box = {};
    $box.$this_2 = $outer;
    super($outer, $box);
    Companion_instance_5.checkPositionIndex_w4k0on_k$(index, this.$this_2.get_size_woubt6_k$());
    this.index_1 = index;
  }
  hasPrevious_qh0629_k$() {
    return this.index_1 > 0;
  }
  nextIndex_jshxun_k$() {
    return this.index_1;
  }
  previous_l2dfd5_k$() {
    if (!this.hasPrevious_qh0629_k$())
      throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
    var tmp = this;
    this.index_1 = this.index_1 - 1 | 0;
    tmp.last_1 = this.index_1;
    return this.$this_2.get_c1px32_k$(this.last_1);
  }
}
class AbstractMutableList extends AbstractMutableCollection {
  static new_kotlin_collections_AbstractMutableList_ddn594_k$() {
    var $this = this.new_kotlin_collections_AbstractMutableCollection_jgoj1k_k$();
    $this.modCount_1 = 0;
    return $this;
  }
  add_utx5q5_k$(element) {
    this.checkIsMutable_jn1ih0_k$();
    this.add_dl6gt3_k$(this.get_size_woubt6_k$(), element);
    return true;
  }
  addAll_h0epyi_k$(index, elements) {
    Companion_instance_5.checkPositionIndex_w4k0on_k$(index, this.get_size_woubt6_k$());
    this.checkIsMutable_jn1ih0_k$();
    var _index = index;
    var changed = false;
    var _iterator__ex2g4s = elements.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var e = _iterator__ex2g4s.next_20eer_k$();
      var _unary__edvuaz = _index;
      _index = _unary__edvuaz + 1 | 0;
      this.add_dl6gt3_k$(_unary__edvuaz, e);
      changed = true;
    }
    return changed;
  }
  clear_j9egeb_k$() {
    this.checkIsMutable_jn1ih0_k$();
    this.removeRange_sm1kzt_k$(0, this.get_size_woubt6_k$());
  }
  removeAll_1fano1_k$(elements) {
    this.checkIsMutable_jn1ih0_k$();
    return removeAll(this, AbstractMutableList$removeAll$lambda(elements));
  }
  retainAll_b9lrv6_k$(elements) {
    this.checkIsMutable_jn1ih0_k$();
    return removeAll(this, AbstractMutableList$retainAll$lambda(elements));
  }
  iterator_jk1svi_k$() {
    return new IteratorImpl(this);
  }
  contains_aljjnj_k$(element) {
    return this.indexOf_si1fv9_k$(element) >= 0;
  }
  indexOf_si1fv9_k$(element) {
    var tmp$ret$1;
    $l$block: {
      // Inline function 'kotlin.collections.indexOfFirst' call
      var index = 0;
      var _iterator__ex2g4s = this.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var item = _iterator__ex2g4s.next_20eer_k$();
        if (equals(item, element)) {
          tmp$ret$1 = index;
          break $l$block;
        }
        index = index + 1 | 0;
      }
      tmp$ret$1 = -1;
    }
    return tmp$ret$1;
  }
  lastIndexOf_v2p1fv_k$(element) {
    var tmp$ret$1;
    $l$block: {
      // Inline function 'kotlin.collections.indexOfLast' call
      var iterator = this.listIterator_70e65o_k$(this.get_size_woubt6_k$());
      while (iterator.hasPrevious_qh0629_k$()) {
        var it = iterator.previous_l2dfd5_k$();
        if (equals(it, element)) {
          tmp$ret$1 = iterator.nextIndex_jshxun_k$();
          break $l$block;
        }
      }
      tmp$ret$1 = -1;
    }
    return tmp$ret$1;
  }
  listIterator_xjshxw_k$() {
    return this.listIterator_70e65o_k$(0);
  }
  listIterator_70e65o_k$(index) {
    return new ListIteratorImpl(this, index);
  }
  subList_xle3r2_k$(fromIndex, toIndex) {
    return SubList.new_kotlin_collections_AbstractMutableList_SubList_kflm75_k$(this, fromIndex, toIndex);
  }
  removeRange_sm1kzt_k$(fromIndex, toIndex) {
    var iterator = this.listIterator_70e65o_k$(fromIndex);
    // Inline function 'kotlin.repeat' call
    var times = toIndex - fromIndex | 0;
    var inductionVariable = 0;
    if (inductionVariable < times)
      do {
        var index = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        iterator.next_20eer_k$();
        iterator.remove_ldkf9o_k$();
      }
       while (inductionVariable < times);
  }
  equals(other) {
    if (other === this)
      return true;
    if (!(!(other == null) ? isInterface(other, KtList) : false))
      return false;
    return Companion_instance_5.orderedEquals_jt170c_k$(this, other);
  }
  hashCode() {
    return Companion_instance_5.orderedHashCode_srkix_k$(this);
  }
}
class RandomAccess {}
class SubList extends AbstractMutableList {
  static new_kotlin_collections_AbstractMutableList_SubList_kflm75_k$(list, fromIndex, toIndex) {
    var $this = this.new_kotlin_collections_AbstractMutableList_ddn594_k$();
    $this.list_1 = list;
    $this.fromIndex_1 = fromIndex;
    $this._size_1 = 0;
    Companion_instance_5.checkRangeIndexes_mmy49x_k$($this.fromIndex_1, toIndex, $this.list_1.get_size_woubt6_k$());
    $this._size_1 = toIndex - $this.fromIndex_1 | 0;
    return $this;
  }
  add_dl6gt3_k$(index, element) {
    Companion_instance_5.checkPositionIndex_w4k0on_k$(index, this._size_1);
    this.list_1.add_dl6gt3_k$(this.fromIndex_1 + index | 0, element);
    this._size_1 = this._size_1 + 1 | 0;
  }
  get_c1px32_k$(index) {
    Companion_instance_5.checkElementIndex_s0yg86_k$(index, this._size_1);
    return this.list_1.get_c1px32_k$(this.fromIndex_1 + index | 0);
  }
  removeAt_6niowx_k$(index) {
    Companion_instance_5.checkElementIndex_s0yg86_k$(index, this._size_1);
    var result = this.list_1.removeAt_6niowx_k$(this.fromIndex_1 + index | 0);
    this._size_1 = this._size_1 - 1 | 0;
    return result;
  }
  set_82063s_k$(index, element) {
    Companion_instance_5.checkElementIndex_s0yg86_k$(index, this._size_1);
    return this.list_1.set_82063s_k$(this.fromIndex_1 + index | 0, element);
  }
  removeRange_sm1kzt_k$(fromIndex, toIndex) {
    this.list_1.removeRange_sm1kzt_k$(this.fromIndex_1 + fromIndex | 0, this.fromIndex_1 + toIndex | 0);
    this._size_1 = this._size_1 - (toIndex - fromIndex | 0) | 0;
  }
  get_size_woubt6_k$() {
    return this._size_1;
  }
  checkIsMutable_jn1ih0_k$() {
    return this.list_1.checkIsMutable_jn1ih0_k$();
  }
}
class AbstractMap {
  static new_kotlin_collections_AbstractMap_5v98o7_k$() {
    var $this = createThis(this);
    $this._keys_1 = null;
    $this._values_1 = null;
    return $this;
  }
  containsKey_aw81wo_k$(key) {
    return !(implFindEntry(this, key) == null);
  }
  containsValue_yf2ykl_k$(value) {
    var tmp0 = this.get_entries_p20ztl_k$();
    var tmp$ret$0;
    $l$block_0: {
      // Inline function 'kotlin.collections.any' call
      var tmp;
      if (isInterface(tmp0, Collection)) {
        tmp = tmp0.isEmpty_y1axqb_k$();
      } else {
        tmp = false;
      }
      if (tmp) {
        tmp$ret$0 = false;
        break $l$block_0;
      }
      var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        if (equals(element.get_value_j01efc_k$(), value)) {
          tmp$ret$0 = true;
          break $l$block_0;
        }
      }
      tmp$ret$0 = false;
    }
    return tmp$ret$0;
  }
  containsEntry_50dpfo_k$(entry) {
    if (!(!(entry == null) ? isInterface(entry, Entry) : false))
      return false;
    var key = entry.get_key_18j28a_k$();
    var value = entry.get_value_j01efc_k$();
    // Inline function 'kotlin.collections.get' call
    var ourValue = (isInterface(this, KtMap) ? this : THROW_CCE()).get_wei43m_k$(key);
    if (!equals(value, ourValue)) {
      return false;
    }
    var tmp;
    if (ourValue == null) {
      // Inline function 'kotlin.collections.containsKey' call
      tmp = !(isInterface(this, KtMap) ? this : THROW_CCE()).containsKey_aw81wo_k$(key);
    } else {
      tmp = false;
    }
    if (tmp) {
      return false;
    }
    return true;
  }
  equals(other) {
    if (other === this)
      return true;
    if (!(!(other == null) ? isInterface(other, KtMap) : false))
      return false;
    if (!(this.get_size_woubt6_k$() === other.get_size_woubt6_k$()))
      return false;
    var tmp0 = other.get_entries_p20ztl_k$();
    var tmp$ret$0;
    $l$block_0: {
      // Inline function 'kotlin.collections.all' call
      var tmp;
      if (isInterface(tmp0, Collection)) {
        tmp = tmp0.isEmpty_y1axqb_k$();
      } else {
        tmp = false;
      }
      if (tmp) {
        tmp$ret$0 = true;
        break $l$block_0;
      }
      var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        if (!this.containsEntry_50dpfo_k$(element)) {
          tmp$ret$0 = false;
          break $l$block_0;
        }
      }
      tmp$ret$0 = true;
    }
    return tmp$ret$0;
  }
  get_wei43m_k$(key) {
    var tmp0_safe_receiver = implFindEntry(this, key);
    return tmp0_safe_receiver == null ? null : tmp0_safe_receiver.get_value_j01efc_k$();
  }
  hashCode() {
    return hashCode(this.get_entries_p20ztl_k$());
  }
  isEmpty_y1axqb_k$() {
    return this.get_size_woubt6_k$() === 0;
  }
  get_size_woubt6_k$() {
    return this.get_entries_p20ztl_k$().get_size_woubt6_k$();
  }
  get_keys_wop4xp_k$() {
    if (this._keys_1 == null) {
      var tmp = this;
      tmp._keys_1 = AbstractMap$keys$1.new_kotlin_collections_AbstractMap__no_name_provided__tjdmmj_k$(this);
    }
    return ensureNotNull(this._keys_1);
  }
  toString() {
    var tmp = this.get_entries_p20ztl_k$();
    return joinToString_1(tmp, ', ', '{', '}', VOID, VOID, AbstractMap$toString$lambda(this));
  }
  get_values_ksazhn_k$() {
    if (this._values_1 == null) {
      var tmp = this;
      tmp._values_1 = AbstractMap$values$1.new_kotlin_collections_AbstractMap__no_name_provided__g3sttz_k$(this);
    }
    return ensureNotNull(this._values_1);
  }
}
class AbstractMutableMap extends AbstractMap {
  static new_kotlin_collections_AbstractMutableMap_wd9kkp_k$() {
    var $this = this.new_kotlin_collections_AbstractMap_5v98o7_k$();
    $this.keysView_1 = null;
    $this.valuesView_1 = null;
    return $this;
  }
  createKeysView_aa1bmb_k$() {
    return HashMapKeysDefault.new_kotlin_collections_HashMapKeysDefault_bct206_k$(this);
  }
  createValuesView_4isqvv_k$() {
    return HashMapValuesDefault.new_kotlin_collections_HashMapValuesDefault_3yszgo_k$(this);
  }
  get_keys_wop4xp_k$() {
    var tmp0_elvis_lhs = this.keysView_1;
    var tmp;
    if (tmp0_elvis_lhs == null) {
      // Inline function 'kotlin.also' call
      var this_0 = this.createKeysView_aa1bmb_k$();
      this.keysView_1 = this_0;
      tmp = this_0;
    } else {
      tmp = tmp0_elvis_lhs;
    }
    return tmp;
  }
  get_values_ksazhn_k$() {
    var tmp0_elvis_lhs = this.valuesView_1;
    var tmp;
    if (tmp0_elvis_lhs == null) {
      // Inline function 'kotlin.also' call
      var this_0 = this.createValuesView_4isqvv_k$();
      this.valuesView_1 = this_0;
      tmp = this_0;
    } else {
      tmp = tmp0_elvis_lhs;
    }
    return tmp;
  }
  clear_j9egeb_k$() {
    this.get_entries_p20ztl_k$().clear_j9egeb_k$();
  }
  putAll_wgg6cj_k$(from) {
    this.checkIsMutable_jn1ih0_k$();
    // Inline function 'kotlin.collections.iterator' call
    var _iterator__ex2g4s = from.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var _destruct__k2r9zo = _iterator__ex2g4s.next_20eer_k$();
      // Inline function 'kotlin.collections.component1' call
      var key = _destruct__k2r9zo.get_key_18j28a_k$();
      // Inline function 'kotlin.collections.component2' call
      var value = _destruct__k2r9zo.get_value_j01efc_k$();
      this.put_4fpzoq_k$(key, value);
    }
  }
  remove_gppy8k_k$(key) {
    this.checkIsMutable_jn1ih0_k$();
    var iter = this.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    while (iter.hasNext_bitz1p_k$()) {
      var entry = iter.next_20eer_k$();
      var k = entry.get_key_18j28a_k$();
      if (equals(key, k)) {
        var value = entry.get_value_j01efc_k$();
        iter.remove_ldkf9o_k$();
        return value;
      }
    }
    return null;
  }
  checkIsMutable_jn1ih0_k$() {
  }
}
class AbstractMutableSet extends AbstractMutableCollection {
  static new_kotlin_collections_AbstractMutableSet_v3jhzq_k$() {
    return this.new_kotlin_collections_AbstractMutableCollection_jgoj1k_k$();
  }
  equals(other) {
    if (other === this)
      return true;
    if (!(!(other == null) ? isInterface(other, KtSet) : false))
      return false;
    return Companion_instance_7.setEquals_mjzluv_k$(this, other);
  }
  hashCode() {
    return Companion_instance_7.unorderedHashCode_8c2ypq_k$(this);
  }
}
class Companion_3 {
  constructor() {
    Companion_instance_3 = this;
    var tmp = this;
    // Inline function 'kotlin.also' call
    var this_0 = ArrayList.new_kotlin_collections_ArrayList_tdd6ob_k$(0);
    this_0.isReadOnly_1 = true;
    tmp.Empty_1 = this_0;
  }
}
class ArrayList extends AbstractMutableList {
  static new_kotlin_collections_ArrayList_qfbsh5_k$(array) {
    Companion_getInstance_3();
    var $this = this.new_kotlin_collections_AbstractMutableList_ddn594_k$();
    $this.array_1 = array;
    $this.isReadOnly_1 = false;
    return $this;
  }
  static new_kotlin_collections_ArrayList_ony0vx_k$() {
    Companion_getInstance_3();
    // Inline function 'kotlin.emptyArray' call
    var tmp$ret$0 = [];
    return this.new_kotlin_collections_ArrayList_qfbsh5_k$(tmp$ret$0);
  }
  static new_kotlin_collections_ArrayList_tdd6ob_k$(initialCapacity) {
    Companion_getInstance_3();
    // Inline function 'kotlin.emptyArray' call
    var tmp$ret$0 = [];
    var $this = this.new_kotlin_collections_ArrayList_qfbsh5_k$(tmp$ret$0);
    // Inline function 'kotlin.require' call
    if (!(initialCapacity >= 0)) {
      var message = 'Negative initial capacity: ' + initialCapacity;
      throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
    }
    return $this;
  }
  static new_kotlin_collections_ArrayList_nk3udn_k$(elements) {
    Companion_getInstance_3();
    // Inline function 'kotlin.collections.toTypedArray' call
    var tmp$ret$0 = copyToArray(elements);
    return this.new_kotlin_collections_ArrayList_qfbsh5_k$(tmp$ret$0);
  }
  build_nmwvly_k$() {
    this.checkIsMutable_jn1ih0_k$();
    this.isReadOnly_1 = true;
    return this.get_size_woubt6_k$() > 0 ? this : Companion_getInstance_3().Empty_1;
  }
  get_size_woubt6_k$() {
    return this.array_1.length;
  }
  get_c1px32_k$(index) {
    var tmp = this.array_1[rangeCheck(this, index)];
    return (tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE();
  }
  set_82063s_k$(index, element) {
    this.checkIsMutable_jn1ih0_k$();
    rangeCheck(this, index);
    // Inline function 'kotlin.apply' call
    var this_0 = this.array_1[index];
    this.array_1[index] = element;
    var tmp = this_0;
    return (tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE();
  }
  add_utx5q5_k$(element) {
    this.checkIsMutable_jn1ih0_k$();
    // Inline function 'kotlin.js.asDynamic' call
    this.array_1.push(element);
    this.modCount_1 = this.modCount_1 + 1 | 0;
    return true;
  }
  add_dl6gt3_k$(index, element) {
    this.checkIsMutable_jn1ih0_k$();
    // Inline function 'kotlin.js.asDynamic' call
    this.array_1.splice(insertionRangeCheck(this, index), 0, element);
    this.modCount_1 = this.modCount_1 + 1 | 0;
  }
  addAll_h3ej1q_k$(elements) {
    this.checkIsMutable_jn1ih0_k$();
    if (elements.isEmpty_y1axqb_k$())
      return false;
    var offset = increaseLength(this, elements.get_size_woubt6_k$());
    // Inline function 'kotlin.collections.forEachIndexed' call
    var index = 0;
    var _iterator__ex2g4s = elements.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var item = _iterator__ex2g4s.next_20eer_k$();
      var _unary__edvuaz = index;
      index = _unary__edvuaz + 1 | 0;
      var index_0 = checkIndexOverflow(_unary__edvuaz);
      this.array_1[offset + index_0 | 0] = item;
    }
    this.modCount_1 = this.modCount_1 + 1 | 0;
    return true;
  }
  addAll_h0epyi_k$(index, elements) {
    this.checkIsMutable_jn1ih0_k$();
    insertionRangeCheck(this, index);
    if (index === this.get_size_woubt6_k$())
      return this.addAll_h3ej1q_k$(elements);
    if (elements.isEmpty_y1axqb_k$())
      return false;
    // Inline function 'kotlin.js.asDynamic' call
    // Inline function 'kotlin.js.unsafeCast' call
    var tail = this.array_1.splice(index);
    this.addAll_h3ej1q_k$(elements);
    var offset = increaseLength(this, tail.length);
    // Inline function 'kotlin.repeat' call
    var times = tail.length;
    var inductionVariable = 0;
    if (inductionVariable < times)
      do {
        var index_0 = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        this.array_1[offset + index_0 | 0] = tail[index_0];
      }
       while (inductionVariable < times);
    this.modCount_1 = this.modCount_1 + 1 | 0;
    return true;
  }
  removeAt_6niowx_k$(index) {
    this.checkIsMutable_jn1ih0_k$();
    rangeCheck(this, index);
    this.modCount_1 = this.modCount_1 + 1 | 0;
    var tmp;
    if (index === get_lastIndex_0(this)) {
      // Inline function 'kotlin.js.asDynamic' call
      tmp = this.array_1.pop();
    } else {
      // Inline function 'kotlin.js.asDynamic' call
      tmp = this.array_1.splice(index, 1)[0];
    }
    return tmp;
  }
  remove_cedx0m_k$(element) {
    this.checkIsMutable_jn1ih0_k$();
    var inductionVariable = 0;
    var last = this.array_1.length - 1 | 0;
    if (inductionVariable <= last)
      do {
        var index = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        if (equals(this.array_1[index], element)) {
          // Inline function 'kotlin.js.asDynamic' call
          this.array_1.splice(index, 1);
          this.modCount_1 = this.modCount_1 + 1 | 0;
          return true;
        }
      }
       while (inductionVariable <= last);
    return false;
  }
  removeRange_sm1kzt_k$(fromIndex, toIndex) {
    this.checkIsMutable_jn1ih0_k$();
    this.modCount_1 = this.modCount_1 + 1 | 0;
    // Inline function 'kotlin.js.asDynamic' call
    this.array_1.splice(fromIndex, toIndex - fromIndex | 0);
  }
  clear_j9egeb_k$() {
    this.checkIsMutable_jn1ih0_k$();
    var tmp = this;
    // Inline function 'kotlin.emptyArray' call
    tmp.array_1 = [];
    this.modCount_1 = this.modCount_1 + 1 | 0;
  }
  indexOf_si1fv9_k$(element) {
    return indexOf(this.array_1, element);
  }
  lastIndexOf_v2p1fv_k$(element) {
    return lastIndexOf(this.array_1, element);
  }
  toString() {
    return arrayToString(this.array_1);
  }
  toArray_jjyjqa_k$() {
    return [].slice.call(this.array_1);
  }
  toArray() {
    return this.toArray_jjyjqa_k$();
  }
  asJsArrayView() {
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    return this.array_1;
  }
  checkIsMutable_jn1ih0_k$() {
    if (this.isReadOnly_1)
      throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
}
class HashMap extends AbstractMutableMap {
  static new_kotlin_collections_HashMap_xg4pkp_k$(internalMap) {
    var $this = this.new_kotlin_collections_AbstractMutableMap_wd9kkp_k$();
    init_kotlin_collections_HashMap($this);
    $this.internalMap_1 = internalMap;
    return $this;
  }
  static new_kotlin_collections_HashMap_2a5kxx_k$() {
    return this.new_kotlin_collections_HashMap_xg4pkp_k$(InternalHashMap.new_kotlin_collections_InternalHashMap_iefrky_k$());
  }
  static new_kotlin_collections_HashMap_kycc7v_k$(initialCapacity, loadFactor) {
    return this.new_kotlin_collections_HashMap_xg4pkp_k$(InternalHashMap.new_kotlin_collections_InternalHashMap_uj162q_k$(initialCapacity, loadFactor));
  }
  static new_kotlin_collections_HashMap_5ewlp_k$(initialCapacity) {
    return this.new_kotlin_collections_HashMap_kycc7v_k$(initialCapacity, 1.0);
  }
  clear_j9egeb_k$() {
    this.internalMap_1.clear_j9egeb_k$();
  }
  containsKey_aw81wo_k$(key) {
    return this.internalMap_1.contains_vbgn2f_k$(key);
  }
  containsValue_yf2ykl_k$(value) {
    return this.internalMap_1.containsValue_yf2ykl_k$(value);
  }
  createKeysView_aa1bmb_k$() {
    return HashMapKeys.new_kotlin_collections_HashMapKeys_s1wvpe_k$(this.internalMap_1);
  }
  createValuesView_4isqvv_k$() {
    return HashMapValues.new_kotlin_collections_HashMapValues_4lttdc_k$(this.internalMap_1);
  }
  get_entries_p20ztl_k$() {
    var tmp0_elvis_lhs = this.entriesView_1;
    var tmp;
    if (tmp0_elvis_lhs == null) {
      // Inline function 'kotlin.also' call
      var this_0 = HashMapEntrySet.new_kotlin_collections_HashMapEntrySet_7nlww9_k$(this.internalMap_1);
      this.entriesView_1 = this_0;
      tmp = this_0;
    } else {
      tmp = tmp0_elvis_lhs;
    }
    return tmp;
  }
  get_wei43m_k$(key) {
    return this.internalMap_1.get_wei43m_k$(key);
  }
  put_4fpzoq_k$(key, value) {
    return this.internalMap_1.put_4fpzoq_k$(key, value);
  }
  remove_gppy8k_k$(key) {
    return this.internalMap_1.remove_gppy8k_k$(key);
  }
  get_size_woubt6_k$() {
    return this.internalMap_1.get_size_woubt6_k$();
  }
  putAll_wgg6cj_k$(from) {
    return this.internalMap_1.putAll_wgg6cj_k$(from);
  }
}
class HashMapKeys extends AbstractMutableSet {
  static new_kotlin_collections_HashMapKeys_s1wvpe_k$(backing) {
    var $this = this.new_kotlin_collections_AbstractMutableSet_v3jhzq_k$();
    $this.backing_1 = backing;
    return $this;
  }
  get_size_woubt6_k$() {
    return this.backing_1.get_size_woubt6_k$();
  }
  isEmpty_y1axqb_k$() {
    return this.backing_1.get_size_woubt6_k$() === 0;
  }
  contains_aljjnj_k$(element) {
    return this.backing_1.contains_vbgn2f_k$(element);
  }
  clear_j9egeb_k$() {
    return this.backing_1.clear_j9egeb_k$();
  }
  add_utx5q5_k$(element) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
  addAll_h3ej1q_k$(elements) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
  iterator_jk1svi_k$() {
    return this.backing_1.keysIterator_mjslfm_k$();
  }
  checkIsMutable_jn1ih0_k$() {
    return this.backing_1.checkIsMutable_h5js84_k$();
  }
}
class HashMapValues extends AbstractMutableCollection {
  static new_kotlin_collections_HashMapValues_4lttdc_k$(backing) {
    var $this = this.new_kotlin_collections_AbstractMutableCollection_jgoj1k_k$();
    $this.backing_1 = backing;
    return $this;
  }
  get_size_woubt6_k$() {
    return this.backing_1.get_size_woubt6_k$();
  }
  isEmpty_y1axqb_k$() {
    return this.backing_1.get_size_woubt6_k$() === 0;
  }
  contains_m22g8e_k$(element) {
    return this.backing_1.containsValue_yf2ykl_k$(element);
  }
  contains_aljjnj_k$(element) {
    if (!(element == null ? true : !(element == null)))
      return false;
    return this.contains_m22g8e_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  add_sqnzo4_k$(element) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
  add_utx5q5_k$(element) {
    return this.add_sqnzo4_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  addAll_h3ejgd_k$(elements) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
  addAll_h3ej1q_k$(elements) {
    return this.addAll_h3ejgd_k$(elements);
  }
  iterator_jk1svi_k$() {
    return this.backing_1.valuesIterator_3ptos0_k$();
  }
  checkIsMutable_jn1ih0_k$() {
    return this.backing_1.checkIsMutable_h5js84_k$();
  }
}
class HashMapEntrySetBase extends AbstractMutableSet {
  static new_kotlin_collections_HashMapEntrySetBase_d1wqzd_k$(backing) {
    var $this = this.new_kotlin_collections_AbstractMutableSet_v3jhzq_k$();
    $this.backing_1 = backing;
    return $this;
  }
  get_size_woubt6_k$() {
    return this.backing_1.get_size_woubt6_k$();
  }
  isEmpty_y1axqb_k$() {
    return this.backing_1.get_size_woubt6_k$() === 0;
  }
  contains_pftbw2_k$(element) {
    return this.backing_1.containsEntry_jg6xfi_k$(element);
  }
  contains_aljjnj_k$(element) {
    if (!(!(element == null) ? isInterface(element, Entry) : false))
      return false;
    return this.contains_pftbw2_k$((!(element == null) ? isInterface(element, Entry) : false) ? element : THROW_CCE());
  }
  clear_j9egeb_k$() {
    return this.backing_1.clear_j9egeb_k$();
  }
  add_k8z7xs_k$(element) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
  add_utx5q5_k$(element) {
    return this.add_k8z7xs_k$((!(element == null) ? isInterface(element, Entry) : false) ? element : THROW_CCE());
  }
  addAll_h3ej1q_k$(elements) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
  containsAll_bwkf3g_k$(elements) {
    return this.backing_1.containsAllEntries_m9iqdx_k$(elements);
  }
  checkIsMutable_jn1ih0_k$() {
    return this.backing_1.checkIsMutable_h5js84_k$();
  }
}
class HashMapEntrySet extends HashMapEntrySetBase {
  static new_kotlin_collections_HashMapEntrySet_7nlww9_k$(backing) {
    return this.new_kotlin_collections_HashMapEntrySetBase_d1wqzd_k$(backing);
  }
  iterator_jk1svi_k$() {
    return this.backing_1.entriesIterator_or017i_k$();
  }
}
class HashMapKeysDefault$iterator$1 {
  constructor($entryIterator) {
    this.$entryIterator_1 = $entryIterator;
  }
  hasNext_bitz1p_k$() {
    return this.$entryIterator_1.hasNext_bitz1p_k$();
  }
  next_20eer_k$() {
    return this.$entryIterator_1.next_20eer_k$().get_key_18j28a_k$();
  }
  remove_ldkf9o_k$() {
    return this.$entryIterator_1.remove_ldkf9o_k$();
  }
}
class HashMapKeysDefault extends AbstractMutableSet {
  static new_kotlin_collections_HashMapKeysDefault_bct206_k$(backingMap) {
    var $this = this.new_kotlin_collections_AbstractMutableSet_v3jhzq_k$();
    $this.backingMap_1 = backingMap;
    return $this;
  }
  add_b330zt_k$(element) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_chzcdl_k$('Add is not supported on keys');
  }
  add_utx5q5_k$(element) {
    return this.add_b330zt_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  clear_j9egeb_k$() {
    return this.backingMap_1.clear_j9egeb_k$();
  }
  contains_vbgn2f_k$(element) {
    return this.backingMap_1.containsKey_aw81wo_k$(element);
  }
  contains_aljjnj_k$(element) {
    if (!(element == null ? true : !(element == null)))
      return false;
    return this.contains_vbgn2f_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  iterator_jk1svi_k$() {
    var entryIterator = this.backingMap_1.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    return new HashMapKeysDefault$iterator$1(entryIterator);
  }
  get_size_woubt6_k$() {
    return this.backingMap_1.get_size_woubt6_k$();
  }
  checkIsMutable_jn1ih0_k$() {
    return this.backingMap_1.checkIsMutable_jn1ih0_k$();
  }
}
class HashMapValuesDefault$iterator$1 {
  constructor($entryIterator) {
    this.$entryIterator_1 = $entryIterator;
  }
  hasNext_bitz1p_k$() {
    return this.$entryIterator_1.hasNext_bitz1p_k$();
  }
  next_20eer_k$() {
    return this.$entryIterator_1.next_20eer_k$().get_value_j01efc_k$();
  }
  remove_ldkf9o_k$() {
    return this.$entryIterator_1.remove_ldkf9o_k$();
  }
}
class HashMapValuesDefault extends AbstractMutableCollection {
  static new_kotlin_collections_HashMapValuesDefault_3yszgo_k$(backingMap) {
    var $this = this.new_kotlin_collections_AbstractMutableCollection_jgoj1k_k$();
    $this.backingMap_1 = backingMap;
    return $this;
  }
  add_sqnzo4_k$(element) {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_chzcdl_k$('Add is not supported on values');
  }
  add_utx5q5_k$(element) {
    return this.add_sqnzo4_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  contains_m22g8e_k$(element) {
    return this.backingMap_1.containsValue_yf2ykl_k$(element);
  }
  contains_aljjnj_k$(element) {
    if (!(element == null ? true : !(element == null)))
      return false;
    return this.contains_m22g8e_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  iterator_jk1svi_k$() {
    var entryIterator = this.backingMap_1.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    return new HashMapValuesDefault$iterator$1(entryIterator);
  }
  get_size_woubt6_k$() {
    return this.backingMap_1.get_size_woubt6_k$();
  }
  checkIsMutable_jn1ih0_k$() {
    return this.backingMap_1.checkIsMutable_jn1ih0_k$();
  }
}
class HashSet extends AbstractMutableSet {
  static new_kotlin_collections_HashSet_1vjklh_k$(map) {
    var $this = this.new_kotlin_collections_AbstractMutableSet_v3jhzq_k$();
    init_kotlin_collections_HashSet($this);
    $this.internalMap_1 = map;
    return $this;
  }
  static new_kotlin_collections_HashSet_ovxcsm_k$() {
    return this.new_kotlin_collections_HashSet_1vjklh_k$(InternalHashMap.new_kotlin_collections_InternalHashMap_iefrky_k$());
  }
  add_utx5q5_k$(element) {
    return this.internalMap_1.put_4fpzoq_k$(element, true) == null;
  }
  clear_j9egeb_k$() {
    this.internalMap_1.clear_j9egeb_k$();
  }
  contains_aljjnj_k$(element) {
    return this.internalMap_1.contains_vbgn2f_k$(element);
  }
  isEmpty_y1axqb_k$() {
    return this.internalMap_1.get_size_woubt6_k$() === 0;
  }
  iterator_jk1svi_k$() {
    return this.internalMap_1.keysIterator_mjslfm_k$();
  }
  get_size_woubt6_k$() {
    return this.internalMap_1.get_size_woubt6_k$();
  }
}
class Companion_4 {
  constructor() {
    this.MAGIC_1 = -1640531527;
    this.INITIAL_CAPACITY_1 = 8;
    this.INITIAL_MAX_PROBE_DISTANCE_1 = 2;
    this.TOMBSTONE_1 = -1;
  }
}
class Itr {
  constructor(map) {
    this.map_1 = map;
    this.index_1 = 0;
    this.lastIndex_1 = -1;
    this.expectedModCount_1 = this.map_1.modCount_1;
    this.initNext_evzkid_k$();
  }
  initNext_evzkid_k$() {
    while (this.index_1 < this.map_1.length_1 && this.map_1.presenceArray_1[this.index_1] < 0) {
      this.index_1 = this.index_1 + 1 | 0;
    }
  }
  hasNext_bitz1p_k$() {
    return this.index_1 < this.map_1.length_1;
  }
  remove_ldkf9o_k$() {
    this.checkForComodification_o4dljl_k$();
    // Inline function 'kotlin.check' call
    if (!!(this.lastIndex_1 === -1)) {
      var message = 'Call next() before removing element from the iterator.';
      throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$(toString_1(message));
    }
    this.map_1.checkIsMutable_h5js84_k$();
    removeEntryAt(this.map_1, this.lastIndex_1);
    this.lastIndex_1 = -1;
    this.expectedModCount_1 = this.map_1.modCount_1;
  }
  checkForComodification_o4dljl_k$() {
    if (!(this.map_1.modCount_1 === this.expectedModCount_1))
      throw ConcurrentModificationException.new_kotlin_ConcurrentModificationException_fy07nh_k$();
  }
}
class KeysItr extends Itr {
  next_20eer_k$() {
    this.checkForComodification_o4dljl_k$();
    if (this.index_1 >= this.map_1.length_1)
      throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
    var tmp = this;
    var _unary__edvuaz = this.index_1;
    this.index_1 = _unary__edvuaz + 1 | 0;
    tmp.lastIndex_1 = _unary__edvuaz;
    var result = this.map_1.keysArray_1[this.lastIndex_1];
    this.initNext_evzkid_k$();
    return result;
  }
}
class ValuesItr extends Itr {
  next_20eer_k$() {
    this.checkForComodification_o4dljl_k$();
    if (this.index_1 >= this.map_1.length_1)
      throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
    var tmp = this;
    var _unary__edvuaz = this.index_1;
    this.index_1 = _unary__edvuaz + 1 | 0;
    tmp.lastIndex_1 = _unary__edvuaz;
    var result = ensureNotNull(this.map_1.valuesArray_1)[this.lastIndex_1];
    this.initNext_evzkid_k$();
    return result;
  }
}
class EntriesItr extends Itr {
  next_20eer_k$() {
    this.checkForComodification_o4dljl_k$();
    if (this.index_1 >= this.map_1.length_1)
      throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
    var tmp = this;
    var _unary__edvuaz = this.index_1;
    this.index_1 = _unary__edvuaz + 1 | 0;
    tmp.lastIndex_1 = _unary__edvuaz;
    var result = new EntryRef(this.map_1, this.lastIndex_1);
    this.initNext_evzkid_k$();
    return result;
  }
  nextHashCode_b13whm_k$() {
    if (this.index_1 >= this.map_1.length_1)
      throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
    var tmp = this;
    var _unary__edvuaz = this.index_1;
    this.index_1 = _unary__edvuaz + 1 | 0;
    tmp.lastIndex_1 = _unary__edvuaz;
    // Inline function 'kotlin.hashCode' call
    var tmp0_safe_receiver = this.map_1.keysArray_1[this.lastIndex_1];
    var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : hashCode(tmp0_safe_receiver);
    var tmp_0 = tmp1_elvis_lhs == null ? 0 : tmp1_elvis_lhs;
    // Inline function 'kotlin.hashCode' call
    var tmp0_safe_receiver_0 = ensureNotNull(this.map_1.valuesArray_1)[this.lastIndex_1];
    var tmp1_elvis_lhs_0 = tmp0_safe_receiver_0 == null ? null : hashCode(tmp0_safe_receiver_0);
    var result = tmp_0 ^ (tmp1_elvis_lhs_0 == null ? 0 : tmp1_elvis_lhs_0);
    this.initNext_evzkid_k$();
    return result;
  }
  nextAppendString_konuli_k$(sb) {
    if (this.index_1 >= this.map_1.length_1)
      throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
    var tmp = this;
    var _unary__edvuaz = this.index_1;
    this.index_1 = _unary__edvuaz + 1 | 0;
    tmp.lastIndex_1 = _unary__edvuaz;
    var key = this.map_1.keysArray_1[this.lastIndex_1];
    if (equals(key, this.map_1))
      sb.append_22ad7x_k$('(this Map)');
    else
      sb.append_t8pm91_k$(key);
    sb.append_t84oo1_k$(_Char___init__impl__6a9atx(61));
    var value = ensureNotNull(this.map_1.valuesArray_1)[this.lastIndex_1];
    if (equals(value, this.map_1))
      sb.append_22ad7x_k$('(this Map)');
    else
      sb.append_t8pm91_k$(value);
    this.initNext_evzkid_k$();
  }
}
class EntryRef {
  constructor(map, index) {
    this.map_1 = map;
    this.index_1 = index;
    this.expectedModCount_1 = this.map_1.modCount_1;
  }
  get_key_18j28a_k$() {
    checkForComodification(this);
    return this.map_1.keysArray_1[this.index_1];
  }
  get_value_j01efc_k$() {
    checkForComodification(this);
    return ensureNotNull(this.map_1.valuesArray_1)[this.index_1];
  }
  equals(other) {
    var tmp;
    var tmp_0;
    if (!(other == null) ? isInterface(other, Entry) : false) {
      tmp_0 = equals(other.get_key_18j28a_k$(), this.get_key_18j28a_k$());
    } else {
      tmp_0 = false;
    }
    if (tmp_0) {
      tmp = equals(other.get_value_j01efc_k$(), this.get_value_j01efc_k$());
    } else {
      tmp = false;
    }
    return tmp;
  }
  hashCode() {
    // Inline function 'kotlin.hashCode' call
    var tmp0_safe_receiver = this.get_key_18j28a_k$();
    var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : hashCode(tmp0_safe_receiver);
    var tmp = tmp1_elvis_lhs == null ? 0 : tmp1_elvis_lhs;
    // Inline function 'kotlin.hashCode' call
    var tmp0_safe_receiver_0 = this.get_value_j01efc_k$();
    var tmp1_elvis_lhs_0 = tmp0_safe_receiver_0 == null ? null : hashCode(tmp0_safe_receiver_0);
    return tmp ^ (tmp1_elvis_lhs_0 == null ? 0 : tmp1_elvis_lhs_0);
  }
  toString() {
    return toString_0(this.get_key_18j28a_k$()) + '=' + toString_0(this.get_value_j01efc_k$());
  }
}
class InternalMap {}
function containsAllEntries(m) {
  var tmp$ret$0;
  $l$block_0: {
    // Inline function 'kotlin.collections.all' call
    var tmp;
    if (isInterface(m, Collection)) {
      tmp = m.isEmpty_y1axqb_k$();
    } else {
      tmp = false;
    }
    if (tmp) {
      tmp$ret$0 = true;
      break $l$block_0;
    }
    var _iterator__ex2g4s = m.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      // Inline function 'kotlin.js.unsafeCast' call
      // Inline function 'kotlin.js.asDynamic' call
      var entry = element;
      var tmp_0;
      if (!(entry == null) ? isInterface(entry, Entry) : false) {
        tmp_0 = this.containsOtherEntry_yvdc55_k$(entry);
      } else {
        tmp_0 = false;
      }
      if (!tmp_0) {
        tmp$ret$0 = false;
        break $l$block_0;
      }
    }
    tmp$ret$0 = true;
  }
  return tmp$ret$0;
}
class InternalHashMap {
  static new_kotlin_collections_InternalHashMap_xusumo_k$(keysArray, valuesArray, presenceArray, hashArray, maxProbeDistance, length) {
    var $this = createThis(this);
    $this.keysArray_1 = keysArray;
    $this.valuesArray_1 = valuesArray;
    $this.presenceArray_1 = presenceArray;
    $this.hashArray_1 = hashArray;
    $this.maxProbeDistance_1 = maxProbeDistance;
    $this.length_1 = length;
    $this.hashShift_1 = computeShift(Companion_instance_4, _get_hashSize__tftcho($this));
    $this.modCount_1 = 0;
    $this._size_1 = 0;
    $this.isReadOnly_1 = false;
    return $this;
  }
  get_size_woubt6_k$() {
    return this._size_1;
  }
  static new_kotlin_collections_InternalHashMap_iefrky_k$() {
    return this.new_kotlin_collections_InternalHashMap_jnbws6_k$(8);
  }
  static new_kotlin_collections_InternalHashMap_jnbws6_k$(initialCapacity) {
    return this.new_kotlin_collections_InternalHashMap_xusumo_k$(arrayOfUninitializedElements(initialCapacity), null, new Int32Array(initialCapacity), new Int32Array(computeHashSize(Companion_instance_4, initialCapacity)), 2, 0);
  }
  static new_kotlin_collections_InternalHashMap_uj162q_k$(initialCapacity, loadFactor) {
    var $this = this.new_kotlin_collections_InternalHashMap_jnbws6_k$(initialCapacity);
    // Inline function 'kotlin.require' call
    if (!(loadFactor > 0)) {
      var message = 'Non-positive load factor: ' + loadFactor;
      throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
    }
    return $this;
  }
  containsValue_yf2ykl_k$(value) {
    return findValue(this, value) >= 0;
  }
  get_wei43m_k$(key) {
    var index = findKey(this, key);
    if (index < 0)
      return null;
    return ensureNotNull(this.valuesArray_1)[index];
  }
  contains_vbgn2f_k$(key) {
    return findKey(this, key) >= 0;
  }
  put_4fpzoq_k$(key, value) {
    var index = addKey(this, key);
    var valuesArray = allocateValuesArray(this);
    if (index < 0) {
      var oldValue = valuesArray[(-index | 0) - 1 | 0];
      valuesArray[(-index | 0) - 1 | 0] = value;
      return oldValue;
    } else {
      valuesArray[index] = value;
      return null;
    }
  }
  putAll_wgg6cj_k$(from) {
    this.checkIsMutable_h5js84_k$();
    putAllEntries(this, from.get_entries_p20ztl_k$());
  }
  remove_gppy8k_k$(key) {
    this.checkIsMutable_h5js84_k$();
    var index = findKey(this, key);
    if (index < 0)
      return null;
    var oldValue = ensureNotNull(this.valuesArray_1)[index];
    removeEntryAt(this, index);
    return oldValue;
  }
  clear_j9egeb_k$() {
    this.checkIsMutable_h5js84_k$();
    var inductionVariable = 0;
    var last = this.length_1 - 1 | 0;
    if (inductionVariable <= last)
      do {
        var i = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        var hash = this.presenceArray_1[i];
        if (hash >= 0) {
          this.hashArray_1[hash] = 0;
          this.presenceArray_1[i] = -1;
        }
      }
       while (!(i === last));
    resetRange(this.keysArray_1, 0, this.length_1);
    var tmp0_safe_receiver = this.valuesArray_1;
    if (tmp0_safe_receiver == null)
      null;
    else {
      resetRange(tmp0_safe_receiver, 0, this.length_1);
    }
    this._size_1 = 0;
    this.length_1 = 0;
    registerModification(this);
  }
  equals(other) {
    var tmp;
    if (other === this) {
      tmp = true;
    } else {
      var tmp_0;
      if (!(other == null) ? isInterface(other, KtMap) : false) {
        tmp_0 = contentEquals(this, other);
      } else {
        tmp_0 = false;
      }
      tmp = tmp_0;
    }
    return tmp;
  }
  hashCode() {
    var result = 0;
    var it = this.entriesIterator_or017i_k$();
    while (it.hasNext_bitz1p_k$()) {
      result = result + it.nextHashCode_b13whm_k$() | 0;
    }
    return result;
  }
  toString() {
    var sb = StringBuilder.new_kotlin_text_StringBuilder_wcb3z_k$(2 + imul_0(this._size_1, 3) | 0);
    sb.append_22ad7x_k$('{');
    var i = 0;
    var it = this.entriesIterator_or017i_k$();
    while (it.hasNext_bitz1p_k$()) {
      if (i > 0) {
        sb.append_22ad7x_k$(', ');
      }
      it.nextAppendString_konuli_k$(sb);
      i = i + 1 | 0;
    }
    sb.append_22ad7x_k$('}');
    return sb.toString();
  }
  checkIsMutable_h5js84_k$() {
    if (this.isReadOnly_1)
      throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
  }
  containsEntry_jg6xfi_k$(entry) {
    var index = findKey(this, entry.get_key_18j28a_k$());
    if (index < 0)
      return false;
    return equals(ensureNotNull(this.valuesArray_1)[index], entry.get_value_j01efc_k$());
  }
  containsOtherEntry_yvdc55_k$(entry) {
    return this.containsEntry_jg6xfi_k$(isInterface(entry, Entry) ? entry : THROW_CCE());
  }
  keysIterator_mjslfm_k$() {
    return new KeysItr(this);
  }
  valuesIterator_3ptos0_k$() {
    return new ValuesItr(this);
  }
  entriesIterator_or017i_k$() {
    return new EntriesItr(this);
  }
}
class LinkedHashMap extends HashMap {
  static new_kotlin_collections_LinkedHashMap_ga0any_k$() {
    var $this = this.new_kotlin_collections_HashMap_2a5kxx_k$();
    init_kotlin_collections_LinkedHashMap($this);
    return $this;
  }
  static new_kotlin_collections_LinkedHashMap_31p40q_k$(initialCapacity) {
    var $this = this.new_kotlin_collections_HashMap_5ewlp_k$(initialCapacity);
    init_kotlin_collections_LinkedHashMap($this);
    return $this;
  }
  checkIsMutable_jn1ih0_k$() {
    return this.internalMap_1.checkIsMutable_h5js84_k$();
  }
}
class LinkedHashSet extends HashSet {
  static new_kotlin_collections_LinkedHashSet_ahyf7j_k$() {
    var $this = this.new_kotlin_collections_HashSet_ovxcsm_k$();
    init_kotlin_collections_LinkedHashSet($this);
    return $this;
  }
  checkIsMutable_jn1ih0_k$() {
    return this.internalMap_1.checkIsMutable_h5js84_k$();
  }
}
class CompletedContinuation {
  get_context_h02k06_k$() {
    var message = 'This continuation is already complete';
    throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$(toString_1(message));
  }
  resumeWith_b9cu3x_k$(result) {
    // Inline function 'kotlin.error' call
    var message = 'This continuation is already complete';
    throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$(toString_1(message));
  }
  resumeWith_dtxwbr_k$(result) {
    return this.resumeWith_b9cu3x_k$(result);
  }
  toString() {
    return 'This continuation is already complete';
  }
}
class InterceptedCoroutine {
  constructor() {
    this._intercepted_1 = null;
  }
  intercepted_vh228x_k$() {
    var tmp0_elvis_lhs = this._intercepted_1;
    var tmp;
    if (tmp0_elvis_lhs == null) {
      var tmp1_safe_receiver = this.get_context_h02k06_k$().get_y2st91_k$(Key_instance);
      var tmp2_elvis_lhs = tmp1_safe_receiver == null ? null : tmp1_safe_receiver.interceptContinuation_3dnmlu_k$(this);
      // Inline function 'kotlin.also' call
      var this_0 = tmp2_elvis_lhs == null ? this : tmp2_elvis_lhs;
      this._intercepted_1 = this_0;
      tmp = this_0;
    } else {
      tmp = tmp0_elvis_lhs;
    }
    return tmp;
  }
  releaseIntercepted_5cyqh6_k$() {
    var intercepted = this._intercepted_1;
    if (!(intercepted == null) && !(intercepted === this)) {
      ensureNotNull(this.get_context_h02k06_k$().get_y2st91_k$(Key_instance)).releaseInterceptedContinuation_rgafzi_k$(intercepted);
    }
    this._intercepted_1 = CompletedContinuation_instance;
  }
}
class GeneratorCoroutineImpl extends InterceptedCoroutine {
  constructor(resultContinuation) {
    super();
    this.resultContinuation_1 = resultContinuation;
    var tmp = this;
    // Inline function 'kotlin.arrayOf' call
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    tmp.jsIterators_1 = [];
    var tmp_0 = this;
    var tmp0_safe_receiver = this.resultContinuation_1;
    tmp_0._context_1 = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.get_context_h02k06_k$();
    this.isRunning_1 = false;
    this.unknown_1 = _Result___init__impl__xyqfz8(Symbol());
    this.savedResult_1 = this.unknown_1;
  }
  get_context_h02k06_k$() {
    return ensureNotNull(this._context_1);
  }
  dropLastIterator_mimyvx_k$() {
    // Inline function 'kotlin.js.asDynamic' call
    this.jsIterators_1.pop();
  }
  addNewIterator_cdx7u0_k$(iterator) {
    // Inline function 'kotlin.js.asDynamic' call
    this.jsIterators_1.push(iterator);
  }
  resumeWith_b9cu3x_k$(result) {
    if (_Result___get_value__impl__bjfvqg(this.unknown_1) === _Result___get_value__impl__bjfvqg(this.savedResult_1))
      this.savedResult_1 = result;
    if (this.isRunning_1)
      return Unit_instance;
    // Inline function 'kotlin.Result.getOrNull' call
    var this_0 = this.savedResult_1;
    var tmp;
    if (_Result___get_isFailure__impl__jpiriv(this_0)) {
      tmp = null;
    } else {
      var tmp_0 = _Result___get_value__impl__bjfvqg(this_0);
      tmp = (tmp_0 == null ? true : !(tmp_0 == null)) ? tmp_0 : THROW_CCE();
    }
    var currentResult = tmp;
    var currentException = Result__exceptionOrNull_impl_p6xea9(this.savedResult_1);
    this.savedResult_1 = this.unknown_1;
    var current = this;
    while (true) {
      $l$loop: while (true) {
        // Inline function 'kotlin.coroutines.GeneratorCoroutineImpl.isCompleted' call
        if (!!(current.jsIterators_1.length === 0)) {
          break $l$loop;
        }
        // Inline function 'kotlin.coroutines.GeneratorCoroutineImpl.getLastIterator' call
        var this_1 = current;
        var jsIterator = this_1.jsIterators_1[this_1.jsIterators_1.length - 1 | 0];
        // Inline function 'kotlin.also' call
        var this_2 = currentException;
        currentException = null;
        var exception = this_2;
        this.isRunning_1 = true;
        try {
          var step = exception == null ? jsIterator.next(currentResult) : jsIterator.throw(exception);
          currentResult = step.value;
          currentException = null;
          if (step.done) {
            current.dropLastIterator_mimyvx_k$();
          }
          if (!(_Result___get_value__impl__bjfvqg(this.unknown_1) === _Result___get_value__impl__bjfvqg(this.savedResult_1))) {
            // Inline function 'kotlin.Result.getOrNull' call
            var this_3 = this.savedResult_1;
            var tmp_1;
            if (_Result___get_isFailure__impl__jpiriv(this_3)) {
              tmp_1 = null;
            } else {
              var tmp_2 = _Result___get_value__impl__bjfvqg(this_3);
              tmp_1 = (tmp_2 == null ? true : !(tmp_2 == null)) ? tmp_2 : THROW_CCE();
            }
            currentResult = tmp_1;
            currentException = Result__exceptionOrNull_impl_p6xea9(this.savedResult_1);
            this.savedResult_1 = this.unknown_1;
          } else if (currentResult === get_COROUTINE_SUSPENDED())
            return Unit_instance;
        } catch ($p) {
          if ($p instanceof Error) {
            var e = $p;
            currentException = e;
            current.dropLastIterator_mimyvx_k$();
          } else {
            throw $p;
          }
        }
        finally {
          this.isRunning_1 = false;
        }
      }
      this.releaseIntercepted_5cyqh6_k$();
      var completion = ensureNotNull(this.resultContinuation_1);
      if (completion instanceof GeneratorCoroutineImpl) {
        current = completion;
      } else {
        var tmp_3;
        if (!(currentException == null)) {
          // Inline function 'kotlin.coroutines.resumeWithException' call
          // Inline function 'kotlin.Companion.failure' call
          var exception_0 = currentException;
          var tmp$ret$6 = _Result___init__impl__xyqfz8(createFailure(exception_0));
          completion.resumeWith_dtxwbr_k$(tmp$ret$6);
          tmp_3 = Unit_instance;
        } else {
          // Inline function 'kotlin.coroutines.resume' call
          // Inline function 'kotlin.Companion.success' call
          var value = currentResult;
          var tmp$ret$8 = _Result___init__impl__xyqfz8(value);
          completion.resumeWith_dtxwbr_k$(tmp$ret$8);
          tmp_3 = Unit_instance;
        }
        return tmp_3;
      }
    }
  }
  resumeWith_dtxwbr_k$(result) {
    return this.resumeWith_b9cu3x_k$(result);
  }
}
class SafeContinuation {
  static new_kotlin_coroutines_SafeContinuation_364ty3_k$(delegate, initialResult) {
    var $this = createThis(this);
    $this.delegate_1 = delegate;
    $this.result_1 = initialResult;
    return $this;
  }
  static new_kotlin_coroutines_SafeContinuation_3yuezq_k$(delegate) {
    return this.new_kotlin_coroutines_SafeContinuation_364ty3_k$(delegate, CoroutineSingletons_UNDECIDED_getInstance());
  }
  get_context_h02k06_k$() {
    return this.delegate_1.get_context_h02k06_k$();
  }
  resumeWith_dtxwbr_k$(result) {
    var cur = this.result_1;
    if (cur === CoroutineSingletons_UNDECIDED_getInstance()) {
      this.result_1 = _Result___get_value__impl__bjfvqg(result);
    } else if (cur === get_COROUTINE_SUSPENDED()) {
      this.result_1 = CoroutineSingletons_RESUMED_getInstance();
      this.delegate_1.resumeWith_dtxwbr_k$(result);
    } else
      throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$('Already resumed');
  }
  getOrThrow_23gqzp_k$() {
    if (this.result_1 === CoroutineSingletons_UNDECIDED_getInstance()) {
      this.result_1 = get_COROUTINE_SUSPENDED();
      return get_COROUTINE_SUSPENDED();
    }
    var result = this.result_1;
    var tmp;
    if (result === CoroutineSingletons_RESUMED_getInstance()) {
      tmp = get_COROUTINE_SUSPENDED();
    } else {
      if (result instanceof Failure) {
        throw result.exception_1;
      } else {
        tmp = result;
      }
    }
    return tmp;
  }
}
class promisify$2$$inlined$Continuation$1 {
  constructor($context, $resolve, $reject) {
    this.$context_1 = $context;
    this.$resolve_1 = $resolve;
    this.$reject_1 = $reject;
  }
  get_context_h02k06_k$() {
    return this.$context_1;
  }
  resumeWith_b9cu3x_k$(result) {
    // Inline function 'kotlin.onSuccess' call
    var action = this.$resolve_1;
    if (_Result___get_isSuccess__impl__sndoy8(result)) {
      var tmp = _Result___get_value__impl__bjfvqg(result);
      action((tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE());
    }
    // Inline function 'kotlin.onFailure' call
    var action_0 = this.$reject_1;
    var tmp0_safe_receiver = Result__exceptionOrNull_impl_p6xea9(result);
    if (tmp0_safe_receiver == null)
      null;
    else {
      // Inline function 'kotlin.let' call
      action_0(tmp0_safe_receiver);
    }
    return Unit_instance;
  }
  resumeWith_dtxwbr_k$(result) {
    return this.resumeWith_b9cu3x_k$(result);
  }
}
class Exception extends Error {
  static new_kotlin_Exception_f32mds_k$() {
    var $this = createExternalThis(this, Error, [null]);
    setPropertiesToThrowableInstance($this);
    init_kotlin_Exception($this);
    return $this;
  }
  static new_kotlin_Exception_hsqbop_k$(message) {
    var $this = createExternalThis(this, Error, [message]);
    setPropertiesToThrowableInstance($this, message);
    init_kotlin_Exception($this);
    return $this;
  }
}
class RuntimeException extends Exception {
  static new_kotlin_RuntimeException_29f9zq_k$() {
    var $this = this.new_kotlin_Exception_f32mds_k$();
    init_kotlin_RuntimeException($this);
    return $this;
  }
  static new_kotlin_RuntimeException_xu1s8h_k$(message) {
    var $this = this.new_kotlin_Exception_hsqbop_k$(message);
    init_kotlin_RuntimeException($this);
    return $this;
  }
}
class UnsupportedOperationException extends RuntimeException {
  static new_kotlin_UnsupportedOperationException_cv3bvm_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_UnsupportedOperationException($this);
    return $this;
  }
  static new_kotlin_UnsupportedOperationException_chzcdl_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_UnsupportedOperationException($this);
    return $this;
  }
}
class IllegalStateException extends RuntimeException {
  static new_kotlin_IllegalStateException_1wtnp1_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_IllegalStateException($this);
    return $this;
  }
  static new_kotlin_IllegalStateException_w47ei6_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_IllegalStateException($this);
    return $this;
  }
}
class IllegalArgumentException extends RuntimeException {
  static new_kotlin_IllegalArgumentException_pv5o3f_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_IllegalArgumentException($this);
    return $this;
  }
  static new_kotlin_IllegalArgumentException_sfqr8_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_IllegalArgumentException($this);
    return $this;
  }
}
class NoSuchElementException extends RuntimeException {
  static new_kotlin_NoSuchElementException_wy3d4q_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_NoSuchElementException($this);
    return $this;
  }
  static new_kotlin_NoSuchElementException_eborbh_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_NoSuchElementException($this);
    return $this;
  }
}
class IndexOutOfBoundsException extends RuntimeException {
  static new_kotlin_IndexOutOfBoundsException_cc7xqw_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_IndexOutOfBoundsException($this);
    return $this;
  }
  static new_kotlin_IndexOutOfBoundsException_ddr8db_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_IndexOutOfBoundsException($this);
    return $this;
  }
}
class ConcurrentModificationException extends RuntimeException {
  static new_kotlin_ConcurrentModificationException_fy07nh_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_ConcurrentModificationException($this);
    return $this;
  }
  static new_kotlin_ConcurrentModificationException_snpq2y_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_ConcurrentModificationException($this);
    return $this;
  }
}
class Error_0 extends Error {
  static new_kotlin_Error_8ce653_k$() {
    var $this = createExternalThis(this, Error, [null]);
    setPropertiesToThrowableInstance($this);
    init_kotlin_Error($this);
    return $this;
  }
  static new_kotlin_Error_cvq542_k$(message) {
    var $this = createExternalThis(this, Error, [message]);
    setPropertiesToThrowableInstance($this, message);
    init_kotlin_Error($this);
    return $this;
  }
}
class ArithmeticException extends RuntimeException {
  static new_kotlin_ArithmeticException_t7nj4q_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_ArithmeticException($this);
    return $this;
  }
  static new_kotlin_ArithmeticException_y2sjkx_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_ArithmeticException($this);
    return $this;
  }
}
class NullPointerException extends RuntimeException {
  static new_kotlin_NullPointerException_q6jd54_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_NullPointerException($this);
    return $this;
  }
}
class UninitializedPropertyAccessException extends RuntimeException {
  static new_kotlin_UninitializedPropertyAccessException_l975ei_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_UninitializedPropertyAccessException($this);
    return $this;
  }
  static new_kotlin_UninitializedPropertyAccessException_egi92l_k$(message) {
    var $this = this.new_kotlin_RuntimeException_xu1s8h_k$(message);
    init_kotlin_UninitializedPropertyAccessException($this);
    return $this;
  }
}
class ClassCastException extends RuntimeException {
  static new_kotlin_ClassCastException_zhuhe1_k$() {
    var $this = this.new_kotlin_RuntimeException_29f9zq_k$();
    init_kotlin_ClassCastException($this);
    return $this;
  }
}
class KClass {}
class KClassImpl {
  equals(other) {
    var tmp;
    if (other instanceof NothingKClassImpl) {
      tmp = false;
    } else {
      if (other instanceof KClassImpl) {
        tmp = equals(this.get_jClass_i6cf5d_k$(), other.get_jClass_i6cf5d_k$());
      } else {
        tmp = false;
      }
    }
    return tmp;
  }
  hashCode() {
    var tmp0_safe_receiver = this.get_simpleName_r6f8py_k$();
    var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : getStringHashCode(tmp0_safe_receiver);
    return tmp1_elvis_lhs == null ? 0 : tmp1_elvis_lhs;
  }
  toString() {
    return 'class ' + this.get_simpleName_r6f8py_k$();
  }
}
class PrimitiveKClassImpl extends KClassImpl {
  constructor(jClass, givenSimpleName, isInstanceFunction) {
    super();
    this.jClass_1 = jClass;
    this.givenSimpleName_1 = givenSimpleName;
    this.isInstanceFunction_1 = isInstanceFunction;
  }
  get_jClass_i6cf5d_k$() {
    return this.jClass_1;
  }
  equals(other) {
    if (!(other instanceof PrimitiveKClassImpl))
      return false;
    return super.equals(other) && this.givenSimpleName_1 === other.givenSimpleName_1;
  }
  get_simpleName_r6f8py_k$() {
    return this.givenSimpleName_1;
  }
}
class NothingKClassImpl extends KClassImpl {
  constructor() {
    NothingKClassImpl_instance = null;
    super();
    NothingKClassImpl_instance = this;
    this.simpleName_1 = 'Nothing';
  }
  get_simpleName_r6f8py_k$() {
    return this.simpleName_1;
  }
  get_jClass_i6cf5d_k$() {
    throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_chzcdl_k$("There's no native JS class for Nothing type");
  }
  equals(other) {
    return other === this;
  }
  hashCode() {
    return 0;
  }
}
class SimpleKClassImpl extends KClassImpl {
  constructor(jClass) {
    super();
    this.jClass_1 = jClass;
    var tmp = this;
    // Inline function 'kotlin.js.asDynamic' call
    var tmp0_safe_receiver = this.jClass_1.$metadata$;
    // Inline function 'kotlin.js.unsafeCast' call
    tmp.simpleName_1 = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.simpleName;
  }
  get_jClass_i6cf5d_k$() {
    return this.jClass_1;
  }
  get_simpleName_r6f8py_k$() {
    return this.simpleName_1;
  }
}
class PrimitiveClasses {
  constructor() {
    PrimitiveClasses_instance = this;
    var tmp = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_0 = Object;
    tmp.anyClass = new PrimitiveKClassImpl(tmp_0, 'Any', PrimitiveClasses$anyClass$lambda);
    var tmp_1 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_2 = Number;
    tmp_1.numberClass = new PrimitiveKClassImpl(tmp_2, 'Number', PrimitiveClasses$numberClass$lambda);
    this.nothingClass = NothingKClassImpl_getInstance();
    var tmp_3 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_4 = Boolean;
    tmp_3.booleanClass = new PrimitiveKClassImpl(tmp_4, 'Boolean', PrimitiveClasses$booleanClass$lambda);
    var tmp_5 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_6 = Number;
    tmp_5.byteClass = new PrimitiveKClassImpl(tmp_6, 'Byte', PrimitiveClasses$byteClass$lambda);
    var tmp_7 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_8 = Number;
    tmp_7.shortClass = new PrimitiveKClassImpl(tmp_8, 'Short', PrimitiveClasses$shortClass$lambda);
    var tmp_9 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_10 = Number;
    tmp_9.intClass = new PrimitiveKClassImpl(tmp_10, 'Int', PrimitiveClasses$intClass$lambda);
    var tmp_11 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    var tmp_12 = typeof BigInt === 'undefined' ? VOID : BigInt;
    tmp_11.longClass = new PrimitiveKClassImpl(tmp_12, 'Long', PrimitiveClasses$longClass$lambda);
    var tmp_13 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_14 = Number;
    tmp_13.floatClass = new PrimitiveKClassImpl(tmp_14, 'Float', PrimitiveClasses$floatClass$lambda);
    var tmp_15 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_16 = Number;
    tmp_15.doubleClass = new PrimitiveKClassImpl(tmp_16, 'Double', PrimitiveClasses$doubleClass$lambda);
    var tmp_17 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_18 = Array;
    tmp_17.arrayClass = new PrimitiveKClassImpl(tmp_18, 'Array', PrimitiveClasses$arrayClass$lambda);
    var tmp_19 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_20 = String;
    tmp_19.stringClass = new PrimitiveKClassImpl(tmp_20, 'String', PrimitiveClasses$stringClass$lambda);
    var tmp_21 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_22 = Error;
    tmp_21.throwableClass = new PrimitiveKClassImpl(tmp_22, 'Throwable', PrimitiveClasses$throwableClass$lambda);
    var tmp_23 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_24 = Array;
    tmp_23.booleanArrayClass = new PrimitiveKClassImpl(tmp_24, 'BooleanArray', PrimitiveClasses$booleanArrayClass$lambda);
    var tmp_25 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_26 = Uint16Array;
    tmp_25.charArrayClass = new PrimitiveKClassImpl(tmp_26, 'CharArray', PrimitiveClasses$charArrayClass$lambda);
    var tmp_27 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_28 = Int8Array;
    tmp_27.byteArrayClass = new PrimitiveKClassImpl(tmp_28, 'ByteArray', PrimitiveClasses$byteArrayClass$lambda);
    var tmp_29 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_30 = Int16Array;
    tmp_29.shortArrayClass = new PrimitiveKClassImpl(tmp_30, 'ShortArray', PrimitiveClasses$shortArrayClass$lambda);
    var tmp_31 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_32 = Int32Array;
    tmp_31.intArrayClass = new PrimitiveKClassImpl(tmp_32, 'IntArray', PrimitiveClasses$intArrayClass$lambda);
    var tmp_33 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_34 = Float32Array;
    tmp_33.floatArrayClass = new PrimitiveKClassImpl(tmp_34, 'FloatArray', PrimitiveClasses$floatArrayClass$lambda);
    var tmp_35 = this;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp_36 = Float64Array;
    tmp_35.doubleArrayClass = new PrimitiveKClassImpl(tmp_36, 'DoubleArray', PrimitiveClasses$doubleArrayClass$lambda);
  }
  get_anyClass_x0jl4l_k$() {
    return this.anyClass;
  }
  get_numberClass_pnym9y_k$() {
    return this.numberClass;
  }
  get_nothingClass_7ivpcc_k$() {
    return this.nothingClass;
  }
  get_booleanClass_d285fr_k$() {
    return this.booleanClass;
  }
  get_byteClass_pu7s61_k$() {
    return this.byteClass;
  }
  get_shortClass_5ajsv9_k$() {
    return this.shortClass;
  }
  get_intClass_mw4y9a_k$() {
    return this.intClass;
  }
  get_longClass_a79cj7_k$() {
    return this.longClass;
  }
  get_floatClass_xlwq2t_k$() {
    return this.floatClass;
  }
  get_doubleClass_dahzcy_k$() {
    return this.doubleClass;
  }
  get_arrayClass_udg0fc_k$() {
    return this.arrayClass;
  }
  get_stringClass_bik2gy_k$() {
    return this.stringClass;
  }
  get_throwableClass_ee1a8x_k$() {
    return this.throwableClass;
  }
  get_booleanArrayClass_lnbwea_k$() {
    return this.booleanArrayClass;
  }
  get_charArrayClass_7lhfoe_k$() {
    return this.charArrayClass;
  }
  get_byteArrayClass_57my8g_k$() {
    return this.byteArrayClass;
  }
  get_shortArrayClass_c1p7wy_k$() {
    return this.shortArrayClass;
  }
  get_intArrayClass_h44pbv_k$() {
    return this.intArrayClass;
  }
  get_floatArrayClass_qngmha_k$() {
    return this.floatArrayClass;
  }
  get_doubleArrayClass_84hee1_k$() {
    return this.doubleArrayClass;
  }
  functionClass(arity) {
    var tmp0_elvis_lhs = get_functionClasses()[arity];
    var tmp;
    if (tmp0_elvis_lhs == null) {
      // Inline function 'kotlin.run' call
      // Inline function 'kotlin.js.unsafeCast' call
      var tmp_0 = Function;
      var tmp_1 = 'Function' + arity;
      var result = new PrimitiveKClassImpl(tmp_0, tmp_1, PrimitiveClasses$functionClass$lambda(arity));
      // Inline function 'kotlin.js.asDynamic' call
      get_functionClasses()[arity] = result;
      tmp = result;
    } else {
      tmp = tmp0_elvis_lhs;
    }
    return tmp;
  }
}
class CharacterCodingException extends Exception {
  static new_kotlin_text_CharacterCodingException_wozcvs_k$(message) {
    var $this = this.new_kotlin_Exception_hsqbop_k$(message);
    captureStack($this, $this.$throwableCtor_2);
    return $this;
  }
  static new_kotlin_text_CharacterCodingException_el5v5_k$() {
    return this.new_kotlin_text_CharacterCodingException_wozcvs_k$(null);
  }
}
class StringBuilder {
  static new_kotlin_text_StringBuilder_7at1nh_k$(content) {
    var $this = createThis(this);
    $this.string_1 = content;
    return $this;
  }
  static new_kotlin_text_StringBuilder_wcb3z_k$(capacity) {
    return this.new_kotlin_text_StringBuilder_u46mrb_k$();
  }
  static new_kotlin_text_StringBuilder_u46mrb_k$() {
    return this.new_kotlin_text_StringBuilder_7at1nh_k$('');
  }
  get_length_g42xv3_k$() {
    // Inline function 'kotlin.js.asDynamic' call
    return this.string_1.length;
  }
  get_kdzpvg_k$(index) {
    // Inline function 'kotlin.text.getOrElse' call
    var this_0 = this.string_1;
    var tmp;
    if (0 <= index ? index <= (charSequenceLength(this_0) - 1 | 0) : false) {
      tmp = charSequenceGet(this_0, index);
    } else {
      throw IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_ddr8db_k$('index: ' + index + ', length: ' + this.get_length_g42xv3_k$() + '}');
    }
    return tmp;
  }
  append_t84oo1_k$(value) {
    this.string_1 = this.string_1 + toString(value);
    return this;
  }
  append_jgojdo_k$(value) {
    this.string_1 = this.string_1 + toString_0(value);
    return this;
  }
  append_t8pm91_k$(value) {
    this.string_1 = this.string_1 + toString_0(value);
    return this;
  }
  append_22ad7x_k$(value) {
    var tmp = this;
    var tmp_0 = this.string_1;
    tmp.string_1 = tmp_0 + (value == null ? 'null' : value);
    return this;
  }
  toString() {
    return this.string_1;
  }
}
class sam$kotlin_Comparator$0 {
  constructor(function_0) {
    this.function_1 = function_0;
  }
  compare_bczr_k$(a, b) {
    return this.function_1(a, b);
  }
  compare(a, b) {
    return this.compare_bczr_k$(a, b);
  }
  getFunctionDelegate_jtodtf_k$() {
    return this.function_1;
  }
  equals(other) {
    var tmp;
    if (!(other == null) ? isInterface(other, Comparator) : false) {
      var tmp_0;
      if (!(other == null) ? isInterface(other, FunctionAdapter) : false) {
        tmp_0 = equals(this.getFunctionDelegate_jtodtf_k$(), other.getFunctionDelegate_jtodtf_k$());
      } else {
        tmp_0 = false;
      }
      tmp = tmp_0;
    } else {
      tmp = false;
    }
    return tmp;
  }
  hashCode() {
    return hashCode(this.getFunctionDelegate_jtodtf_k$());
  }
}
class Companion_5 {
  constructor() {
    this.maxArraySize_1 = 2147483639;
  }
  checkElementIndex_s0yg86_k$(index, size) {
    if (index < 0 || index >= size) {
      throw IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_ddr8db_k$('index: ' + index + ', size: ' + size);
    }
  }
  checkPositionIndex_w4k0on_k$(index, size) {
    if (index < 0 || index > size) {
      throw IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_ddr8db_k$('index: ' + index + ', size: ' + size);
    }
  }
  checkRangeIndexes_mmy49x_k$(fromIndex, toIndex, size) {
    if (fromIndex < 0 || toIndex > size) {
      throw IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_ddr8db_k$('fromIndex: ' + fromIndex + ', toIndex: ' + toIndex + ', size: ' + size);
    }
    if (fromIndex > toIndex) {
      throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$('fromIndex: ' + fromIndex + ' > toIndex: ' + toIndex);
    }
  }
  newCapacity_k5ozfy_k$(oldCapacity, minCapacity) {
    var newCapacity = oldCapacity + (oldCapacity >> 1) | 0;
    if ((newCapacity - minCapacity | 0) < 0)
      newCapacity = minCapacity;
    if ((newCapacity - 2147483639 | 0) > 0)
      newCapacity = minCapacity > 2147483639 ? 2147483647 : 2147483639;
    return newCapacity;
  }
  orderedHashCode_srkix_k$(c) {
    var hashCode_0 = 1;
    var _iterator__ex2g4s = c.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var e = _iterator__ex2g4s.next_20eer_k$();
      var tmp = imul_0(31, hashCode_0);
      var tmp1_elvis_lhs = e == null ? null : hashCode(e);
      hashCode_0 = tmp + (tmp1_elvis_lhs == null ? 0 : tmp1_elvis_lhs) | 0;
    }
    return hashCode_0;
  }
  orderedEquals_jt170c_k$(c, other) {
    if (!(c.get_size_woubt6_k$() === other.get_size_woubt6_k$()))
      return false;
    var otherIterator = other.iterator_jk1svi_k$();
    var _iterator__ex2g4s = c.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var elem = _iterator__ex2g4s.next_20eer_k$();
      var elemOther = otherIterator.next_20eer_k$();
      if (!equals(elem, elemOther)) {
        return false;
      }
    }
    return true;
  }
}
class AbstractMap$keys$1$iterator$1 {
  constructor($entryIterator) {
    this.$entryIterator_1 = $entryIterator;
  }
  hasNext_bitz1p_k$() {
    return this.$entryIterator_1.hasNext_bitz1p_k$();
  }
  next_20eer_k$() {
    return this.$entryIterator_1.next_20eer_k$().get_key_18j28a_k$();
  }
}
class AbstractMap$values$1$iterator$1 {
  constructor($entryIterator) {
    this.$entryIterator_1 = $entryIterator;
  }
  hasNext_bitz1p_k$() {
    return this.$entryIterator_1.hasNext_bitz1p_k$();
  }
  next_20eer_k$() {
    return this.$entryIterator_1.next_20eer_k$().get_value_j01efc_k$();
  }
}
class Companion_6 {}
class AbstractSet extends AbstractCollection {
  static new_kotlin_collections_AbstractSet_l10baj_k$($box) {
    return this.new_kotlin_collections_AbstractCollection_s1tlv0_k$($box);
  }
  equals(other) {
    if (other === this)
      return true;
    if (!(!(other == null) ? isInterface(other, KtSet) : false))
      return false;
    return Companion_instance_7.setEquals_mjzluv_k$(this, other);
  }
  hashCode() {
    return Companion_instance_7.unorderedHashCode_8c2ypq_k$(this);
  }
}
class AbstractMap$keys$1 extends AbstractSet {
  static new_kotlin_collections_AbstractMap__no_name_provided__tjdmmj_k$(this$0, $box) {
    if ($box === VOID)
      $box = {};
    $box.this$0__1 = this$0;
    return this.new_kotlin_collections_AbstractSet_l10baj_k$($box);
  }
  contains_vbgn2f_k$(element) {
    return this.this$0__1.containsKey_aw81wo_k$(element);
  }
  contains_aljjnj_k$(element) {
    if (!(element == null ? true : !(element == null)))
      return false;
    return this.contains_vbgn2f_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  iterator_jk1svi_k$() {
    var entryIterator = this.this$0__1.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    return new AbstractMap$keys$1$iterator$1(entryIterator);
  }
  get_size_woubt6_k$() {
    return this.this$0__1.get_size_woubt6_k$();
  }
}
class AbstractMap$values$1 extends AbstractCollection {
  static new_kotlin_collections_AbstractMap__no_name_provided__g3sttz_k$(this$0, $box) {
    if ($box === VOID)
      $box = {};
    $box.this$0__1 = this$0;
    return this.new_kotlin_collections_AbstractCollection_s1tlv0_k$($box);
  }
  contains_m22g8e_k$(element) {
    return this.this$0__1.containsValue_yf2ykl_k$(element);
  }
  contains_aljjnj_k$(element) {
    if (!(element == null ? true : !(element == null)))
      return false;
    return this.contains_m22g8e_k$((element == null ? true : !(element == null)) ? element : THROW_CCE());
  }
  iterator_jk1svi_k$() {
    var entryIterator = this.this$0__1.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    return new AbstractMap$values$1$iterator$1(entryIterator);
  }
  get_size_woubt6_k$() {
    return this.this$0__1.get_size_woubt6_k$();
  }
}
class Companion_7 {
  unorderedHashCode_8c2ypq_k$(c) {
    var hashCode_0 = 0;
    var _iterator__ex2g4s = c.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      var tmp = hashCode_0;
      var tmp1_elvis_lhs = element == null ? null : hashCode(element);
      hashCode_0 = tmp + (tmp1_elvis_lhs == null ? 0 : tmp1_elvis_lhs) | 0;
    }
    return hashCode_0;
  }
  setEquals_mjzluv_k$(c, other) {
    if (!(c.get_size_woubt6_k$() === other.get_size_woubt6_k$()))
      return false;
    return c.containsAll_bwkf3g_k$(other);
  }
}
class Companion_8 {
  constructor() {
    Companion_instance_8 = this;
    var tmp = this;
    // Inline function 'kotlin.emptyArray' call
    tmp.emptyElementData_1 = [];
    this.defaultMinCapacity_1 = 10;
  }
}
class ArrayDeque extends AbstractMutableList {
  get_size_woubt6_k$() {
    return this.size_1;
  }
  static new_kotlin_collections_ArrayDeque_sf0swv_k$() {
    Companion_getInstance_8();
    var $this = this.new_kotlin_collections_AbstractMutableList_ddn594_k$();
    init_kotlin_collections_ArrayDeque($this);
    $this.elementData_1 = Companion_getInstance_8().emptyElementData_1;
    return $this;
  }
  isEmpty_y1axqb_k$() {
    return this.size_1 === 0;
  }
  last_1z1cm_k$() {
    var tmp;
    if (this.isEmpty_y1axqb_k$()) {
      throw NoSuchElementException.new_kotlin_NoSuchElementException_eborbh_k$('ArrayDeque is empty.');
    } else {
      // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
      var index = get_lastIndex_0(this);
      // Inline function 'kotlin.collections.ArrayDeque.internalGet' call
      var internalIndex = positiveMod(this, this.head_1 + index | 0);
      var tmp_0 = this.elementData_1[internalIndex];
      tmp = (tmp_0 == null ? true : !(tmp_0 == null)) ? tmp_0 : THROW_CCE();
    }
    return tmp;
  }
  addFirst_7io6zl_k$(element) {
    registerModification_0(this);
    ensureCapacity_0(this, this.size_1 + 1 | 0);
    this.head_1 = decremented(this, this.head_1);
    this.elementData_1[this.head_1] = element;
    this.size_1 = this.size_1 + 1 | 0;
  }
  addLast_gaaijb_k$(element) {
    registerModification_0(this);
    ensureCapacity_0(this, this.size_1 + 1 | 0);
    var tmp = this.elementData_1;
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var index = this.size_1;
    tmp[positiveMod(this, this.head_1 + index | 0)] = element;
    this.size_1 = this.size_1 + 1 | 0;
  }
  removeFirst_58pi0k_k$() {
    if (this.isEmpty_y1axqb_k$())
      throw NoSuchElementException.new_kotlin_NoSuchElementException_eborbh_k$('ArrayDeque is empty.');
    registerModification_0(this);
    // Inline function 'kotlin.collections.ArrayDeque.internalGet' call
    var internalIndex = this.head_1;
    var tmp = this.elementData_1[internalIndex];
    var element = (tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE();
    this.elementData_1[this.head_1] = null;
    this.head_1 = incremented(this, this.head_1);
    this.size_1 = this.size_1 - 1 | 0;
    return element;
  }
  removeLast_i5wx8a_k$() {
    if (this.isEmpty_y1axqb_k$())
      throw NoSuchElementException.new_kotlin_NoSuchElementException_eborbh_k$('ArrayDeque is empty.');
    registerModification_0(this);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var index = get_lastIndex_0(this);
    var internalLastIndex = positiveMod(this, this.head_1 + index | 0);
    // Inline function 'kotlin.collections.ArrayDeque.internalGet' call
    var tmp = this.elementData_1[internalLastIndex];
    var element = (tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE();
    this.elementData_1[internalLastIndex] = null;
    this.size_1 = this.size_1 - 1 | 0;
    return element;
  }
  add_utx5q5_k$(element) {
    this.addLast_gaaijb_k$(element);
    return true;
  }
  add_dl6gt3_k$(index, element) {
    Companion_instance_5.checkPositionIndex_w4k0on_k$(index, this.size_1);
    if (index === this.size_1) {
      this.addLast_gaaijb_k$(element);
      return Unit_instance;
    } else if (index === 0) {
      this.addFirst_7io6zl_k$(element);
      return Unit_instance;
    }
    registerModification_0(this);
    ensureCapacity_0(this, this.size_1 + 1 | 0);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var internalIndex = positiveMod(this, this.head_1 + index | 0);
    if (index < (this.size_1 + 1 | 0) >> 1) {
      var decrementedInternalIndex = decremented(this, internalIndex);
      var decrementedHead = decremented(this, this.head_1);
      if (decrementedInternalIndex >= this.head_1) {
        this.elementData_1[decrementedHead] = this.elementData_1[this.head_1];
        var tmp0 = this.elementData_1;
        var tmp2 = this.elementData_1;
        var tmp4 = this.head_1;
        var tmp6 = this.head_1 + 1 | 0;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex = decrementedInternalIndex + 1 | 0;
        arrayCopy(tmp0, tmp2, tmp4, tmp6, endIndex);
      } else {
        var tmp0_0 = this.elementData_1;
        var tmp2_0 = this.elementData_1;
        var tmp4_0 = this.head_1 - 1 | 0;
        var tmp6_0 = this.head_1;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex_0 = this.elementData_1.length;
        arrayCopy(tmp0_0, tmp2_0, tmp4_0, tmp6_0, endIndex_0);
        this.elementData_1[this.elementData_1.length - 1 | 0] = this.elementData_1[0];
        var tmp0_1 = this.elementData_1;
        var tmp2_1 = this.elementData_1;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex_1 = decrementedInternalIndex + 1 | 0;
        arrayCopy(tmp0_1, tmp2_1, 0, 1, endIndex_1);
      }
      this.elementData_1[decrementedInternalIndex] = element;
      this.head_1 = decrementedHead;
    } else {
      // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
      var index_0 = this.size_1;
      var tail = positiveMod(this, this.head_1 + index_0 | 0);
      if (internalIndex < tail) {
        var tmp0_2 = this.elementData_1;
        var tmp2_2 = this.elementData_1;
        // Inline function 'kotlin.collections.copyInto' call
        var destinationOffset = internalIndex + 1 | 0;
        arrayCopy(tmp0_2, tmp2_2, destinationOffset, internalIndex, tail);
      } else {
        var tmp0_3 = this.elementData_1;
        // Inline function 'kotlin.collections.copyInto' call
        var destination = this.elementData_1;
        arrayCopy(tmp0_3, destination, 1, 0, tail);
        this.elementData_1[0] = this.elementData_1[this.elementData_1.length - 1 | 0];
        var tmp0_4 = this.elementData_1;
        var tmp2_3 = this.elementData_1;
        var tmp4_1 = internalIndex + 1 | 0;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex_2 = this.elementData_1.length - 1 | 0;
        arrayCopy(tmp0_4, tmp2_3, tmp4_1, internalIndex, endIndex_2);
      }
      this.elementData_1[internalIndex] = element;
    }
    this.size_1 = this.size_1 + 1 | 0;
  }
  addAll_h3ej1q_k$(elements) {
    if (elements.isEmpty_y1axqb_k$())
      return false;
    registerModification_0(this);
    ensureCapacity_0(this, this.size_1 + elements.get_size_woubt6_k$() | 0);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var index = this.size_1;
    var tmp$ret$0 = positiveMod(this, this.head_1 + index | 0);
    copyCollectionElements(this, tmp$ret$0, elements);
    return true;
  }
  addAll_h0epyi_k$(index, elements) {
    Companion_instance_5.checkPositionIndex_w4k0on_k$(index, this.size_1);
    if (elements.isEmpty_y1axqb_k$()) {
      return false;
    } else if (index === this.size_1) {
      return this.addAll_h3ej1q_k$(elements);
    }
    registerModification_0(this);
    ensureCapacity_0(this, this.size_1 + elements.get_size_woubt6_k$() | 0);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var index_0 = this.size_1;
    var tail = positiveMod(this, this.head_1 + index_0 | 0);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var internalIndex = positiveMod(this, this.head_1 + index | 0);
    var elementsSize = elements.get_size_woubt6_k$();
    if (index < (this.size_1 + 1 | 0) >> 1) {
      var shiftedHead = this.head_1 - elementsSize | 0;
      if (internalIndex >= this.head_1) {
        if (shiftedHead >= 0) {
          var tmp0 = this.elementData_1;
          var tmp2 = this.elementData_1;
          var tmp4 = shiftedHead;
          // Inline function 'kotlin.collections.copyInto' call
          var startIndex = this.head_1;
          arrayCopy(tmp0, tmp2, tmp4, startIndex, internalIndex);
        } else {
          shiftedHead = shiftedHead + this.elementData_1.length | 0;
          var elementsToShift = internalIndex - this.head_1 | 0;
          var shiftToBack = this.elementData_1.length - shiftedHead | 0;
          if (shiftToBack >= elementsToShift) {
            var tmp0_0 = this.elementData_1;
            var tmp2_0 = this.elementData_1;
            var tmp4_0 = shiftedHead;
            // Inline function 'kotlin.collections.copyInto' call
            var startIndex_0 = this.head_1;
            arrayCopy(tmp0_0, tmp2_0, tmp4_0, startIndex_0, internalIndex);
          } else {
            var tmp0_1 = this.elementData_1;
            var tmp2_1 = this.elementData_1;
            var tmp4_1 = shiftedHead;
            var tmp6 = this.head_1;
            // Inline function 'kotlin.collections.copyInto' call
            var endIndex = this.head_1 + shiftToBack | 0;
            arrayCopy(tmp0_1, tmp2_1, tmp4_1, tmp6, endIndex);
            var tmp0_2 = this.elementData_1;
            var tmp2_2 = this.elementData_1;
            // Inline function 'kotlin.collections.copyInto' call
            var startIndex_1 = this.head_1 + shiftToBack | 0;
            arrayCopy(tmp0_2, tmp2_2, 0, startIndex_1, internalIndex);
          }
        }
      } else {
        var tmp0_3 = this.elementData_1;
        var tmp2_3 = this.elementData_1;
        var tmp4_2 = shiftedHead;
        var tmp6_0 = this.head_1;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex_0 = this.elementData_1.length;
        arrayCopy(tmp0_3, tmp2_3, tmp4_2, tmp6_0, endIndex_0);
        if (elementsSize >= internalIndex) {
          var tmp0_4 = this.elementData_1;
          var tmp2_4 = this.elementData_1;
          // Inline function 'kotlin.collections.copyInto' call
          var destinationOffset = this.elementData_1.length - elementsSize | 0;
          arrayCopy(tmp0_4, tmp2_4, destinationOffset, 0, internalIndex);
        } else {
          var tmp0_5 = this.elementData_1;
          var tmp2_5 = this.elementData_1;
          // Inline function 'kotlin.collections.copyInto' call
          var destinationOffset_0 = this.elementData_1.length - elementsSize | 0;
          arrayCopy(tmp0_5, tmp2_5, destinationOffset_0, 0, elementsSize);
          var tmp0_6 = this.elementData_1;
          // Inline function 'kotlin.collections.copyInto' call
          var destination = this.elementData_1;
          arrayCopy(tmp0_6, destination, 0, elementsSize, internalIndex);
        }
      }
      this.head_1 = shiftedHead;
      copyCollectionElements(this, negativeMod(this, internalIndex - elementsSize | 0), elements);
    } else {
      var shiftedInternalIndex = internalIndex + elementsSize | 0;
      if (internalIndex < tail) {
        if ((tail + elementsSize | 0) <= this.elementData_1.length) {
          var tmp0_7 = this.elementData_1;
          // Inline function 'kotlin.collections.copyInto' call
          var destination_0 = this.elementData_1;
          arrayCopy(tmp0_7, destination_0, shiftedInternalIndex, internalIndex, tail);
        } else {
          if (shiftedInternalIndex >= this.elementData_1.length) {
            var tmp0_8 = this.elementData_1;
            var tmp2_6 = this.elementData_1;
            // Inline function 'kotlin.collections.copyInto' call
            var destinationOffset_1 = shiftedInternalIndex - this.elementData_1.length | 0;
            arrayCopy(tmp0_8, tmp2_6, destinationOffset_1, internalIndex, tail);
          } else {
            var shiftToFront = (tail + elementsSize | 0) - this.elementData_1.length | 0;
            var tmp0_9 = this.elementData_1;
            var tmp2_7 = this.elementData_1;
            // Inline function 'kotlin.collections.copyInto' call
            var startIndex_2 = tail - shiftToFront | 0;
            arrayCopy(tmp0_9, tmp2_7, 0, startIndex_2, tail);
            var tmp0_10 = this.elementData_1;
            var tmp2_8 = this.elementData_1;
            // Inline function 'kotlin.collections.copyInto' call
            var endIndex_1 = tail - shiftToFront | 0;
            arrayCopy(tmp0_10, tmp2_8, shiftedInternalIndex, internalIndex, endIndex_1);
          }
        }
      } else {
        var tmp0_11 = this.elementData_1;
        // Inline function 'kotlin.collections.copyInto' call
        var destination_1 = this.elementData_1;
        arrayCopy(tmp0_11, destination_1, elementsSize, 0, tail);
        if (shiftedInternalIndex >= this.elementData_1.length) {
          var tmp0_12 = this.elementData_1;
          var tmp2_9 = this.elementData_1;
          var tmp4_3 = shiftedInternalIndex - this.elementData_1.length | 0;
          // Inline function 'kotlin.collections.copyInto' call
          var endIndex_2 = this.elementData_1.length;
          arrayCopy(tmp0_12, tmp2_9, tmp4_3, internalIndex, endIndex_2);
        } else {
          var tmp0_13 = this.elementData_1;
          var tmp2_10 = this.elementData_1;
          var tmp6_1 = this.elementData_1.length - elementsSize | 0;
          // Inline function 'kotlin.collections.copyInto' call
          var endIndex_3 = this.elementData_1.length;
          arrayCopy(tmp0_13, tmp2_10, 0, tmp6_1, endIndex_3);
          var tmp0_14 = this.elementData_1;
          var tmp2_11 = this.elementData_1;
          // Inline function 'kotlin.collections.copyInto' call
          var endIndex_4 = this.elementData_1.length - elementsSize | 0;
          arrayCopy(tmp0_14, tmp2_11, shiftedInternalIndex, internalIndex, endIndex_4);
        }
      }
      copyCollectionElements(this, internalIndex, elements);
    }
    return true;
  }
  get_c1px32_k$(index) {
    Companion_instance_5.checkElementIndex_s0yg86_k$(index, this.size_1);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    // Inline function 'kotlin.collections.ArrayDeque.internalGet' call
    var internalIndex = positiveMod(this, this.head_1 + index | 0);
    var tmp = this.elementData_1[internalIndex];
    return (tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE();
  }
  set_82063s_k$(index, element) {
    Companion_instance_5.checkElementIndex_s0yg86_k$(index, this.size_1);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var internalIndex = positiveMod(this, this.head_1 + index | 0);
    // Inline function 'kotlin.collections.ArrayDeque.internalGet' call
    var tmp = this.elementData_1[internalIndex];
    var oldElement = (tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE();
    this.elementData_1[internalIndex] = element;
    return oldElement;
  }
  contains_aljjnj_k$(element) {
    return !(this.indexOf_si1fv9_k$(element) === -1);
  }
  indexOf_si1fv9_k$(element) {
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var index = this.size_1;
    var tail = positiveMod(this, this.head_1 + index | 0);
    if (this.head_1 < tail) {
      var inductionVariable = this.head_1;
      if (inductionVariable < tail)
        do {
          var index_0 = inductionVariable;
          inductionVariable = inductionVariable + 1 | 0;
          if (equals(element, this.elementData_1[index_0]))
            return index_0 - this.head_1 | 0;
        }
         while (inductionVariable < tail);
    } else {
      var tmp;
      // Inline function 'kotlin.collections.isNotEmpty' call
      if (!this.isEmpty_y1axqb_k$()) {
        tmp = this.head_1 >= tail;
      } else {
        tmp = false;
      }
      if (tmp) {
        var inductionVariable_0 = this.head_1;
        var last = this.elementData_1.length;
        if (inductionVariable_0 < last)
          do {
            var index_1 = inductionVariable_0;
            inductionVariable_0 = inductionVariable_0 + 1 | 0;
            if (equals(element, this.elementData_1[index_1]))
              return index_1 - this.head_1 | 0;
          }
           while (inductionVariable_0 < last);
        var inductionVariable_1 = 0;
        if (inductionVariable_1 < tail)
          do {
            var index_2 = inductionVariable_1;
            inductionVariable_1 = inductionVariable_1 + 1 | 0;
            if (equals(element, this.elementData_1[index_2]))
              return (index_2 + this.elementData_1.length | 0) - this.head_1 | 0;
          }
           while (inductionVariable_1 < tail);
      }
    }
    return -1;
  }
  lastIndexOf_v2p1fv_k$(element) {
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var index = this.size_1;
    var tail = positiveMod(this, this.head_1 + index | 0);
    if (this.head_1 < tail) {
      var inductionVariable = tail - 1 | 0;
      var last = this.head_1;
      if (last <= inductionVariable)
        do {
          var index_0 = inductionVariable;
          inductionVariable = inductionVariable + -1 | 0;
          if (equals(element, this.elementData_1[index_0]))
            return index_0 - this.head_1 | 0;
        }
         while (!(index_0 === last));
    } else {
      var tmp;
      // Inline function 'kotlin.collections.isNotEmpty' call
      if (!this.isEmpty_y1axqb_k$()) {
        tmp = this.head_1 >= tail;
      } else {
        tmp = false;
      }
      if (tmp) {
        var inductionVariable_0 = tail - 1 | 0;
        if (0 <= inductionVariable_0)
          do {
            var index_1 = inductionVariable_0;
            inductionVariable_0 = inductionVariable_0 + -1 | 0;
            if (equals(element, this.elementData_1[index_1]))
              return (index_1 + this.elementData_1.length | 0) - this.head_1 | 0;
          }
           while (0 <= inductionVariable_0);
        var inductionVariable_1 = get_lastIndex(this.elementData_1);
        var last_0 = this.head_1;
        if (last_0 <= inductionVariable_1)
          do {
            var index_2 = inductionVariable_1;
            inductionVariable_1 = inductionVariable_1 + -1 | 0;
            if (equals(element, this.elementData_1[index_2]))
              return index_2 - this.head_1 | 0;
          }
           while (!(index_2 === last_0));
      }
    }
    return -1;
  }
  remove_cedx0m_k$(element) {
    var index = this.indexOf_si1fv9_k$(element);
    if (index === -1)
      return false;
    this.removeAt_6niowx_k$(index);
    return true;
  }
  removeAt_6niowx_k$(index) {
    Companion_instance_5.checkElementIndex_s0yg86_k$(index, this.size_1);
    if (index === get_lastIndex_0(this)) {
      return this.removeLast_i5wx8a_k$();
    } else if (index === 0) {
      return this.removeFirst_58pi0k_k$();
    }
    registerModification_0(this);
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var internalIndex = positiveMod(this, this.head_1 + index | 0);
    // Inline function 'kotlin.collections.ArrayDeque.internalGet' call
    var tmp = this.elementData_1[internalIndex];
    var element = (tmp == null ? true : !(tmp == null)) ? tmp : THROW_CCE();
    if (index < this.size_1 >> 1) {
      if (internalIndex >= this.head_1) {
        var tmp0 = this.elementData_1;
        var tmp2 = this.elementData_1;
        var tmp4 = this.head_1 + 1 | 0;
        // Inline function 'kotlin.collections.copyInto' call
        var startIndex = this.head_1;
        arrayCopy(tmp0, tmp2, tmp4, startIndex, internalIndex);
      } else {
        var tmp0_0 = this.elementData_1;
        // Inline function 'kotlin.collections.copyInto' call
        var destination = this.elementData_1;
        arrayCopy(tmp0_0, destination, 1, 0, internalIndex);
        this.elementData_1[0] = this.elementData_1[this.elementData_1.length - 1 | 0];
        var tmp0_1 = this.elementData_1;
        var tmp2_0 = this.elementData_1;
        var tmp4_0 = this.head_1 + 1 | 0;
        var tmp6 = this.head_1;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex = this.elementData_1.length - 1 | 0;
        arrayCopy(tmp0_1, tmp2_0, tmp4_0, tmp6, endIndex);
      }
      this.elementData_1[this.head_1] = null;
      this.head_1 = incremented(this, this.head_1);
    } else {
      // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
      var index_0 = get_lastIndex_0(this);
      var internalLastIndex = positiveMod(this, this.head_1 + index_0 | 0);
      if (internalIndex <= internalLastIndex) {
        var tmp0_2 = this.elementData_1;
        var tmp2_1 = this.elementData_1;
        var tmp6_0 = internalIndex + 1 | 0;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex_0 = internalLastIndex + 1 | 0;
        arrayCopy(tmp0_2, tmp2_1, internalIndex, tmp6_0, endIndex_0);
      } else {
        var tmp0_3 = this.elementData_1;
        var tmp2_2 = this.elementData_1;
        var tmp6_1 = internalIndex + 1 | 0;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex_1 = this.elementData_1.length;
        arrayCopy(tmp0_3, tmp2_2, internalIndex, tmp6_1, endIndex_1);
        this.elementData_1[this.elementData_1.length - 1 | 0] = this.elementData_1[0];
        var tmp0_4 = this.elementData_1;
        var tmp2_3 = this.elementData_1;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex_2 = internalLastIndex + 1 | 0;
        arrayCopy(tmp0_4, tmp2_3, 0, 1, endIndex_2);
      }
      this.elementData_1[internalLastIndex] = null;
    }
    this.size_1 = this.size_1 - 1 | 0;
    return element;
  }
  removeAll_1fano1_k$(elements) {
    var tmp$ret$1;
    $l$block: {
      // Inline function 'kotlin.collections.ArrayDeque.filterInPlace' call
      var tmp;
      if (this.isEmpty_y1axqb_k$()) {
        tmp = true;
      } else {
        // Inline function 'kotlin.collections.isEmpty' call
        tmp = this.elementData_1.length === 0;
      }
      if (tmp) {
        tmp$ret$1 = false;
        break $l$block;
      }
      // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
      var index = this.size_1;
      var tail = positiveMod(this, this.head_1 + index | 0);
      var newTail = this.head_1;
      var modified = false;
      if (this.head_1 < tail) {
        var inductionVariable = this.head_1;
        if (inductionVariable < tail)
          do {
            var index_0 = inductionVariable;
            inductionVariable = inductionVariable + 1 | 0;
            var element = this.elementData_1[index_0];
            var it = (element == null ? true : !(element == null)) ? element : THROW_CCE();
            if (!elements.contains_aljjnj_k$(it)) {
              var tmp_0 = this.elementData_1;
              var _unary__edvuaz = newTail;
              newTail = _unary__edvuaz + 1 | 0;
              tmp_0[_unary__edvuaz] = element;
            } else {
              modified = true;
            }
          }
           while (inductionVariable < tail);
        fill(this.elementData_1, null, newTail, tail);
      } else {
        var inductionVariable_0 = this.head_1;
        var last = this.elementData_1.length;
        if (inductionVariable_0 < last)
          do {
            var index_1 = inductionVariable_0;
            inductionVariable_0 = inductionVariable_0 + 1 | 0;
            var element_0 = this.elementData_1[index_1];
            this.elementData_1[index_1] = null;
            var it_0 = (element_0 == null ? true : !(element_0 == null)) ? element_0 : THROW_CCE();
            if (!elements.contains_aljjnj_k$(it_0)) {
              var tmp_1 = this.elementData_1;
              var _unary__edvuaz_0 = newTail;
              newTail = _unary__edvuaz_0 + 1 | 0;
              tmp_1[_unary__edvuaz_0] = element_0;
            } else {
              modified = true;
            }
          }
           while (inductionVariable_0 < last);
        newTail = positiveMod(this, newTail);
        var inductionVariable_1 = 0;
        if (inductionVariable_1 < tail)
          do {
            var index_2 = inductionVariable_1;
            inductionVariable_1 = inductionVariable_1 + 1 | 0;
            var element_1 = this.elementData_1[index_2];
            this.elementData_1[index_2] = null;
            var it_1 = (element_1 == null ? true : !(element_1 == null)) ? element_1 : THROW_CCE();
            if (!elements.contains_aljjnj_k$(it_1)) {
              this.elementData_1[newTail] = element_1;
              newTail = incremented(this, newTail);
            } else {
              modified = true;
            }
          }
           while (inductionVariable_1 < tail);
      }
      if (modified) {
        registerModification_0(this);
        this.size_1 = negativeMod(this, newTail - this.head_1 | 0);
      }
      tmp$ret$1 = modified;
    }
    return tmp$ret$1;
  }
  retainAll_b9lrv6_k$(elements) {
    var tmp$ret$1;
    $l$block: {
      // Inline function 'kotlin.collections.ArrayDeque.filterInPlace' call
      var tmp;
      if (this.isEmpty_y1axqb_k$()) {
        tmp = true;
      } else {
        // Inline function 'kotlin.collections.isEmpty' call
        tmp = this.elementData_1.length === 0;
      }
      if (tmp) {
        tmp$ret$1 = false;
        break $l$block;
      }
      // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
      var index = this.size_1;
      var tail = positiveMod(this, this.head_1 + index | 0);
      var newTail = this.head_1;
      var modified = false;
      if (this.head_1 < tail) {
        var inductionVariable = this.head_1;
        if (inductionVariable < tail)
          do {
            var index_0 = inductionVariable;
            inductionVariable = inductionVariable + 1 | 0;
            var element = this.elementData_1[index_0];
            var it = (element == null ? true : !(element == null)) ? element : THROW_CCE();
            if (elements.contains_aljjnj_k$(it)) {
              var tmp_0 = this.elementData_1;
              var _unary__edvuaz = newTail;
              newTail = _unary__edvuaz + 1 | 0;
              tmp_0[_unary__edvuaz] = element;
            } else {
              modified = true;
            }
          }
           while (inductionVariable < tail);
        fill(this.elementData_1, null, newTail, tail);
      } else {
        var inductionVariable_0 = this.head_1;
        var last = this.elementData_1.length;
        if (inductionVariable_0 < last)
          do {
            var index_1 = inductionVariable_0;
            inductionVariable_0 = inductionVariable_0 + 1 | 0;
            var element_0 = this.elementData_1[index_1];
            this.elementData_1[index_1] = null;
            var it_0 = (element_0 == null ? true : !(element_0 == null)) ? element_0 : THROW_CCE();
            if (elements.contains_aljjnj_k$(it_0)) {
              var tmp_1 = this.elementData_1;
              var _unary__edvuaz_0 = newTail;
              newTail = _unary__edvuaz_0 + 1 | 0;
              tmp_1[_unary__edvuaz_0] = element_0;
            } else {
              modified = true;
            }
          }
           while (inductionVariable_0 < last);
        newTail = positiveMod(this, newTail);
        var inductionVariable_1 = 0;
        if (inductionVariable_1 < tail)
          do {
            var index_2 = inductionVariable_1;
            inductionVariable_1 = inductionVariable_1 + 1 | 0;
            var element_1 = this.elementData_1[index_2];
            this.elementData_1[index_2] = null;
            var it_1 = (element_1 == null ? true : !(element_1 == null)) ? element_1 : THROW_CCE();
            if (elements.contains_aljjnj_k$(it_1)) {
              this.elementData_1[newTail] = element_1;
              newTail = incremented(this, newTail);
            } else {
              modified = true;
            }
          }
           while (inductionVariable_1 < tail);
      }
      if (modified) {
        registerModification_0(this);
        this.size_1 = negativeMod(this, newTail - this.head_1 | 0);
      }
      tmp$ret$1 = modified;
    }
    return tmp$ret$1;
  }
  clear_j9egeb_k$() {
    // Inline function 'kotlin.collections.isNotEmpty' call
    if (!this.isEmpty_y1axqb_k$()) {
      registerModification_0(this);
      // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
      var index = this.size_1;
      var tail = positiveMod(this, this.head_1 + index | 0);
      nullifyNonEmpty(this, this.head_1, tail);
    }
    this.head_1 = 0;
    this.size_1 = 0;
  }
  toArray_6cwqme_k$(array) {
    var tmp = array.length >= this.size_1 ? array : arrayOfNulls(array, this.size_1);
    var dest = isArray(tmp) ? tmp : THROW_CCE();
    // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
    var index = this.size_1;
    var tail = positiveMod(this, this.head_1 + index | 0);
    if (this.head_1 < tail) {
      var tmp0 = this.elementData_1;
      // Inline function 'kotlin.collections.copyInto' call
      var startIndex = this.head_1;
      arrayCopy(tmp0, dest, 0, startIndex, tail);
    } else {
      // Inline function 'kotlin.collections.isNotEmpty' call
      if (!this.isEmpty_y1axqb_k$()) {
        var tmp0_0 = this.elementData_1;
        var tmp6 = this.head_1;
        // Inline function 'kotlin.collections.copyInto' call
        var endIndex = this.elementData_1.length;
        arrayCopy(tmp0_0, dest, 0, tmp6, endIndex);
        var tmp0_1 = this.elementData_1;
        // Inline function 'kotlin.collections.copyInto' call
        var destinationOffset = this.elementData_1.length - this.head_1 | 0;
        arrayCopy(tmp0_1, dest, destinationOffset, 0, tail);
      }
    }
    var tmp_0 = terminateCollectionToArray(this.size_1, dest);
    return isArray(tmp_0) ? tmp_0 : THROW_CCE();
  }
  toArray_jjyjqa_k$() {
    // Inline function 'kotlin.arrayOfNulls' call
    var size = this.size_1;
    var tmp$ret$0 = Array(size);
    return this.toArray_6cwqme_k$(tmp$ret$0);
  }
  toArray() {
    return this.toArray_jjyjqa_k$();
  }
  removeRange_sm1kzt_k$(fromIndex, toIndex) {
    Companion_instance_5.checkRangeIndexes_mmy49x_k$(fromIndex, toIndex, this.size_1);
    var length = toIndex - fromIndex | 0;
    if (length === 0)
      return Unit_instance;
    else if (length === this.size_1) {
      this.clear_j9egeb_k$();
      return Unit_instance;
    } else if (length === 1) {
      this.removeAt_6niowx_k$(fromIndex);
      return Unit_instance;
    }
    registerModification_0(this);
    if (fromIndex < (this.size_1 - toIndex | 0)) {
      removeRangeShiftPreceding(this, fromIndex, toIndex);
      var newHead = positiveMod(this, this.head_1 + length | 0);
      nullifyNonEmpty(this, this.head_1, newHead);
      this.head_1 = newHead;
    } else {
      removeRangeShiftSucceeding(this, fromIndex, toIndex);
      // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
      var index = this.size_1;
      var tail = positiveMod(this, this.head_1 + index | 0);
      nullifyNonEmpty(this, negativeMod(this, tail - length | 0), tail);
    }
    this.size_1 = this.size_1 - length | 0;
  }
}
class EmptyList {
  constructor() {
    this.serialVersionUID_1 = -7390468764508069838n;
  }
  equals(other) {
    var tmp;
    if (!(other == null) ? isInterface(other, KtList) : false) {
      tmp = other.isEmpty_y1axqb_k$();
    } else {
      tmp = false;
    }
    return tmp;
  }
  hashCode() {
    return 1;
  }
  toString() {
    return '[]';
  }
  get_size_woubt6_k$() {
    return 0;
  }
  isEmpty_y1axqb_k$() {
    return true;
  }
  contains_a7ux40_k$(element) {
    return false;
  }
  contains_aljjnj_k$(element) {
    if (!false)
      return false;
    var tmp;
    if (false) {
      tmp = element;
    } else {
      tmp = THROW_CCE();
    }
    return this.contains_a7ux40_k$(tmp);
  }
  containsAll_4yme17_k$(elements) {
    return elements.isEmpty_y1axqb_k$();
  }
  containsAll_bwkf3g_k$(elements) {
    return this.containsAll_4yme17_k$(elements);
  }
  get_c1px32_k$(index) {
    throw IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_ddr8db_k$("Empty list doesn't contain element at index " + index + '.');
  }
  indexOf_31ms1i_k$(element) {
    return -1;
  }
  indexOf_si1fv9_k$(element) {
    if (!false)
      return -1;
    var tmp;
    if (false) {
      tmp = element;
    } else {
      tmp = THROW_CCE();
    }
    return this.indexOf_31ms1i_k$(tmp);
  }
  lastIndexOf_5pkqqc_k$(element) {
    return -1;
  }
  lastIndexOf_v2p1fv_k$(element) {
    if (!false)
      return -1;
    var tmp;
    if (false) {
      tmp = element;
    } else {
      tmp = THROW_CCE();
    }
    return this.lastIndexOf_5pkqqc_k$(tmp);
  }
  iterator_jk1svi_k$() {
    return EmptyIterator_instance;
  }
  listIterator_xjshxw_k$() {
    return EmptyIterator_instance;
  }
  listIterator_70e65o_k$(index) {
    if (!(index === 0))
      throw IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_ddr8db_k$('Index: ' + index);
    return EmptyIterator_instance;
  }
  subList_xle3r2_k$(fromIndex, toIndex) {
    if (fromIndex === 0 && toIndex === 0)
      return this;
    throw IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_ddr8db_k$('fromIndex: ' + fromIndex + ', toIndex: ' + toIndex);
  }
}
class EmptyIterator {
  hasNext_bitz1p_k$() {
    return false;
  }
  hasPrevious_qh0629_k$() {
    return false;
  }
  nextIndex_jshxun_k$() {
    return 0;
  }
  next_20eer_k$() {
    throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
  }
  previous_l2dfd5_k$() {
    throw NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$();
  }
}
class EmptyMap {
  constructor() {
    this.serialVersionUID_1 = 8246714829545688274n;
  }
  equals(other) {
    var tmp;
    if (!(other == null) ? isInterface(other, KtMap) : false) {
      tmp = other.isEmpty_y1axqb_k$();
    } else {
      tmp = false;
    }
    return tmp;
  }
  hashCode() {
    return 0;
  }
  toString() {
    return '{}';
  }
  get_size_woubt6_k$() {
    return 0;
  }
  isEmpty_y1axqb_k$() {
    return true;
  }
  containsKey_v2r3nj_k$(key) {
    return false;
  }
  containsKey_aw81wo_k$(key) {
    if (!(key == null ? true : !(key == null)))
      return false;
    return this.containsKey_v2r3nj_k$((key == null ? true : !(key == null)) ? key : THROW_CCE());
  }
  get_eccq09_k$(key) {
    return null;
  }
  get_wei43m_k$(key) {
    if (!(key == null ? true : !(key == null)))
      return null;
    return this.get_eccq09_k$((key == null ? true : !(key == null)) ? key : THROW_CCE());
  }
  get_entries_p20ztl_k$() {
    return EmptySet_instance;
  }
  get_keys_wop4xp_k$() {
    return EmptySet_instance;
  }
  get_values_ksazhn_k$() {
    return EmptyList_instance;
  }
}
class EmptySet {
  constructor() {
    this.serialVersionUID_1 = 3406603774387020532n;
  }
  equals(other) {
    var tmp;
    if (!(other == null) ? isInterface(other, KtSet) : false) {
      tmp = other.isEmpty_y1axqb_k$();
    } else {
      tmp = false;
    }
    return tmp;
  }
  hashCode() {
    return 0;
  }
  toString() {
    return '[]';
  }
  get_size_woubt6_k$() {
    return 0;
  }
  isEmpty_y1axqb_k$() {
    return true;
  }
  contains_a7ux40_k$(element) {
    return false;
  }
  contains_aljjnj_k$(element) {
    if (!false)
      return false;
    var tmp;
    if (false) {
      tmp = element;
    } else {
      tmp = THROW_CCE();
    }
    return this.contains_a7ux40_k$(tmp);
  }
  containsAll_4yme17_k$(elements) {
    return elements.isEmpty_y1axqb_k$();
  }
  containsAll_bwkf3g_k$(elements) {
    return this.containsAll_4yme17_k$(elements);
  }
  iterator_jk1svi_k$() {
    return EmptyIterator_instance;
  }
}
class Key {}
class ContinuationInterceptor {}
function releaseInterceptedContinuation(continuation) {
}
class EmptyCoroutineContext {
  constructor() {
    this.serialVersionUID_1 = 0n;
  }
  get_y2st91_k$(key) {
    return null;
  }
  hashCode() {
    return 0;
  }
  toString() {
    return 'EmptyCoroutineContext';
  }
}
class CoroutineSingletons extends Enum {}
class Companion_9 {}
class Failure {
  constructor(exception) {
    this.exception_1 = exception;
  }
  equals(other) {
    var tmp;
    if (other instanceof Failure) {
      tmp = equals(this.exception_1, other.exception_1);
    } else {
      tmp = false;
    }
    return tmp;
  }
  hashCode() {
    return hashCode(this.exception_1);
  }
  toString() {
    return 'Failure(' + this.exception_1.toString() + ')';
  }
}
class NotImplementedError extends Error_0 {
  static new_kotlin_NotImplementedError_8rzvsv_k$(message) {
    message = message === VOID ? 'An operation is not implemented.' : message;
    var $this = this.new_kotlin_Error_cvq542_k$(message);
    captureStack($this, $this.$throwableCtor_2);
    return $this;
  }
}
class Pair {
  constructor(first, second) {
    this.first_1 = first;
    this.second_1 = second;
  }
  toString() {
    return '(' + toString_0(this.first_1) + ', ' + toString_0(this.second_1) + ')';
  }
  component1_7eebsc_k$() {
    return this.first_1;
  }
  component2_7eebsb_k$() {
    return this.second_1;
  }
  hashCode() {
    var result = this.first_1 == null ? 0 : hashCode(this.first_1);
    result = imul_0(result, 31) + (this.second_1 == null ? 0 : hashCode(this.second_1)) | 0;
    return result;
  }
  equals(other) {
    if (this === other)
      return true;
    if (!(other instanceof Pair))
      return false;
    if (!equals(this.first_1, other.first_1))
      return false;
    if (!equals(this.second_1, other.second_1))
      return false;
    return true;
  }
}
class Companion_10 {
  constructor() {
    Companion_instance_10 = this;
    this.NIL_1 = new Uuid(0n, 0n);
    this.SIZE_BYTES_1 = 16;
    this.SIZE_BITS_1 = 128;
  }
  fromLongs_uu1aj7_k$(mostSignificantBits, leastSignificantBits) {
    var tmp;
    if (mostSignificantBits === 0n && leastSignificantBits === 0n) {
      tmp = this.NIL_1;
    } else {
      tmp = new Uuid(mostSignificantBits, leastSignificantBits);
    }
    return tmp;
  }
  fromByteArray_d3r0u1_k$(byteArray) {
    // Inline function 'kotlin.require' call
    if (!(byteArray.length === 16)) {
      var message = 'Expected exactly 16 bytes, but was ' + truncateForErrorMessage(byteArray, 32) + ' of size ' + byteArray.length;
      throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
    }
    return this.fromLongs_uu1aj7_k$(getLongAt(byteArray, 0), getLongAt(byteArray, 8));
  }
  random_fimq0t_k$() {
    return this.generateV4_5206hf_k$();
  }
  generateV4_5206hf_k$() {
    return secureRandomUuid();
  }
}
class Uuid {
  constructor(mostSignificantBits, leastSignificantBits) {
    Companion_getInstance_10();
    this.mostSignificantBits_1 = mostSignificantBits;
    this.leastSignificantBits_1 = leastSignificantBits;
  }
  toString() {
    return this.toHexDashString_ptqfb7_k$();
  }
  toHexDashString_ptqfb7_k$() {
    var bytes = new Int8Array(36);
    formatBytesInto(this.mostSignificantBits_1, bytes, 0, 0, 4);
    // Inline function 'kotlin.code' call
    var this_0 = _Char___init__impl__6a9atx(45);
    var tmp$ret$0 = Char__toInt_impl_vasixd(this_0);
    bytes[8] = toByte(tmp$ret$0);
    formatBytesInto(this.mostSignificantBits_1, bytes, 9, 4, 6);
    // Inline function 'kotlin.code' call
    var this_1 = _Char___init__impl__6a9atx(45);
    var tmp$ret$1 = Char__toInt_impl_vasixd(this_1);
    bytes[13] = toByte(tmp$ret$1);
    formatBytesInto(this.mostSignificantBits_1, bytes, 14, 6, 8);
    // Inline function 'kotlin.code' call
    var this_2 = _Char___init__impl__6a9atx(45);
    var tmp$ret$2 = Char__toInt_impl_vasixd(this_2);
    bytes[18] = toByte(tmp$ret$2);
    formatBytesInto(this.leastSignificantBits_1, bytes, 19, 0, 2);
    // Inline function 'kotlin.code' call
    var this_3 = _Char___init__impl__6a9atx(45);
    var tmp$ret$3 = Char__toInt_impl_vasixd(this_3);
    bytes[23] = toByte(tmp$ret$3);
    formatBytesInto(this.leastSignificantBits_1, bytes, 24, 2, 8);
    return decodeToString(bytes);
  }
  equals(other) {
    if (this === other)
      return true;
    if (!(other instanceof Uuid))
      return false;
    return this.mostSignificantBits_1 === other.mostSignificantBits_1 && this.leastSignificantBits_1 === other.leastSignificantBits_1;
  }
  compareTo_f67i9s_k$(other) {
    var tmp;
    if (!(this.mostSignificantBits_1 === other.mostSignificantBits_1)) {
      // Inline function 'kotlin.toULong' call
      var this_0 = this.mostSignificantBits_1;
      var tmp0 = _ULong___init__impl__c78o9k(this_0);
      // Inline function 'kotlin.toULong' call
      var this_1 = other.mostSignificantBits_1;
      // Inline function 'kotlin.ULong.compareTo' call
      var other_0 = _ULong___init__impl__c78o9k(this_1);
      tmp = ulongCompare(_ULong___get_data__impl__fggpzb(tmp0), _ULong___get_data__impl__fggpzb(other_0));
    } else {
      // Inline function 'kotlin.toULong' call
      var this_2 = this.leastSignificantBits_1;
      var tmp0_0 = _ULong___init__impl__c78o9k(this_2);
      // Inline function 'kotlin.toULong' call
      var this_3 = other.leastSignificantBits_1;
      // Inline function 'kotlin.ULong.compareTo' call
      var other_1 = _ULong___init__impl__c78o9k(this_3);
      tmp = ulongCompare(_ULong___get_data__impl__fggpzb(tmp0_0), _ULong___get_data__impl__fggpzb(other_1));
    }
    return tmp;
  }
  compareTo_hpufkf_k$(other) {
    return this.compareTo_f67i9s_k$(other instanceof Uuid ? other : THROW_CCE());
  }
  hashCode() {
    return getBigIntHashCode(this.mostSignificantBits_1 ^ this.leastSignificantBits_1);
  }
}
class atomicfu$TraceBase {
  atomicfu$Trace$append$1(event) {
  }
  atomicfu$Trace$append$2(event1, event2) {
  }
  atomicfu$Trace$append$3(event1, event2, event3) {
  }
  atomicfu$Trace$append$4(event1, event2, event3, event4) {
  }
}
class None extends atomicfu$TraceBase {
  constructor() {
    None_instance = null;
    super();
    None_instance = this;
  }
}
class AtomicInt {
  constructor(value) {
    this.kotlinx$atomicfu$value = value;
  }
  set_kotlinx$atomicfu$value_nm6d3_k$(_set____db54di) {
    this.kotlinx$atomicfu$value = _set____db54di;
  }
  get_kotlinx$atomicfu$value_vi2am5_k$() {
    return this.kotlinx$atomicfu$value;
  }
  atomicfu$compareAndSet(expect, update) {
    if (!(this.kotlinx$atomicfu$value === expect))
      return false;
    this.kotlinx$atomicfu$value = update;
    return true;
  }
  atomicfu$getAndSet(value) {
    var oldValue = this.kotlinx$atomicfu$value;
    this.kotlinx$atomicfu$value = value;
    return oldValue;
  }
  atomicfu$getAndIncrement() {
    var _unary__edvuaz = this.kotlinx$atomicfu$value;
    this.kotlinx$atomicfu$value = _unary__edvuaz + 1 | 0;
    return _unary__edvuaz;
  }
  atomicfu$getAndDecrement() {
    var _unary__edvuaz = this.kotlinx$atomicfu$value;
    this.kotlinx$atomicfu$value = _unary__edvuaz - 1 | 0;
    return _unary__edvuaz;
  }
  atomicfu$getAndAdd(delta) {
    var oldValue = this.kotlinx$atomicfu$value;
    this.kotlinx$atomicfu$value = this.kotlinx$atomicfu$value + delta | 0;
    return oldValue;
  }
  atomicfu$addAndGet(delta) {
    this.kotlinx$atomicfu$value = this.kotlinx$atomicfu$value + delta | 0;
    return this.kotlinx$atomicfu$value;
  }
  atomicfu$incrementAndGet() {
    this.kotlinx$atomicfu$value = this.kotlinx$atomicfu$value + 1 | 0;
    return this.kotlinx$atomicfu$value;
  }
  atomicfu$decrementAndGet() {
    this.kotlinx$atomicfu$value = this.kotlinx$atomicfu$value - 1 | 0;
    return this.kotlinx$atomicfu$value;
  }
  toString() {
    return this.kotlinx$atomicfu$value.toString();
  }
}
class Unique {}
class UniqueImpl {
  constructor(id, label) {
    id = id === VOID ? Companion_getInstance_10().random_fimq0t_k$() : id;
    label = label === VOID ? '' : label;
    this.id_1 = id;
    this.label_1 = label;
  }
  get_id_kntnx8_k$() {
    return this.id_1;
  }
  get_label_iuj8p7_k$() {
    return this.label_1;
  }
  get id() {
    return this.get_id_kntnx8_k$();
  }
  get label() {
    return this.get_label_iuj8p7_k$();
  }
}
class Visitable {}
class Edge {
  constructor(source, consumer, destination, provider) {
    this.$$delegate_0__1 = new UniqueImpl();
    this.source = source;
    this.consumer = consumer;
    this.destination = destination;
    this.provider = provider;
    // Inline function 'kotlin.require' call
    if (!!this.consumer.isConnected()) {
      var message = 'Consumer is already connected.';
      throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
    }
    this.consumer.edge = this;
    var tmp0 = this.provider.edges;
    // Inline function 'kotlin.collections.set' call
    var key = this.consumer;
    tmp0.put_4fpzoq_k$(key, this);
    this.source.emit(new Connected(this.consumer, this.provider));
    this.destination.emit(new Connected(this.provider, this.consumer));
  }
  get_source_jl0x7o_k$() {
    return this.source;
  }
  get_consumer_tu5133_k$() {
    return this.consumer;
  }
  get_destination_9r3c63_k$() {
    return this.destination;
  }
  get_provider_mw8vcq_k$() {
    return this.provider;
  }
  invoke(fn) {
    // Inline function 'dev.shibasis.reaktor.portgraph.port.ProviderPort.invoke' call
    return fn(this.provider.impl);
  }
  suspended(fn) {
    return promisify(($completion) => this.suspended$suspendBridge_p37mc8_k$(fn, $completion));
  }
  suspended$suspendBridge_p37mc8_k$(fn, $completion) {
    return suspendOrReturn(/*#__NOINLINE__*/_generator_suspended$suspendBridge__ucnhzs.bind(VOID, this, fn), $completion);
  }
  suspended_n6q0t5_k$(fn, $completion) {
    return suspendOrReturn(/*#__NOINLINE__*/_generator_suspended__vg2ce1.bind(VOID, this, fn), $completion);
  }
  toString() {
    var tmp = this.source;
    var tmp0_safe_receiver = isInterface(tmp, Unique) ? tmp : null;
    var tmp_0;
    if (tmp0_safe_receiver == null) {
      tmp_0 = null;
    } else {
      // Inline function 'kotlin.let' call
      tmp_0 = tmp0_safe_receiver.label + ' (' + tmp0_safe_receiver.id.toString() + ')';
    }
    var tmp1_elvis_lhs = tmp_0;
    var src = tmp1_elvis_lhs == null ? 'Unknown' : tmp1_elvis_lhs;
    var tmp_1 = this.destination;
    var tmp2_safe_receiver = isInterface(tmp_1, Unique) ? tmp_1 : null;
    var tmp_2;
    if (tmp2_safe_receiver == null) {
      tmp_2 = null;
    } else {
      // Inline function 'kotlin.let' call
      tmp_2 = tmp2_safe_receiver.label + ' (' + tmp2_safe_receiver.id.toString() + ')';
    }
    var tmp3_elvis_lhs = tmp_2;
    var dest = tmp3_elvis_lhs == null ? 'Unknown' : tmp3_elvis_lhs;
    return '[Edge] ' + this.id.toString() + ': ' + src + '.' + this.consumer.key.toString() + ' -> ' + dest + '.' + this.provider.key.toString();
  }
  get_id_kntnx8_k$() {
    return this.$$delegate_0__1.id;
  }
  get_label_iuj8p7_k$() {
    return this.$$delegate_0__1.label;
  }
  get id() {
    return this.get_id_kntnx8_k$();
  }
  get label() {
    return this.get_label_iuj8p7_k$();
  }
}
class PortGraph {
  constructor(id, label) {
    id = id === VOID ? Companion_getInstance_10().random_fimq0t_k$() : id;
    label = label === VOID ? '' : label;
    this.id_1 = id;
    this.label_1 = label;
    var tmp = this;
    // Inline function 'kotlin.collections.arrayListOf' call
    tmp.nodes = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
  }
  get_id_kntnx8_k$() {
    return this.id_1;
  }
  get_label_iuj8p7_k$() {
    return this.label_1;
  }
  get_nodes_ivvt6w_k$() {
    return this.nodes;
  }
  attach(node) {
    var tmp0 = this.nodes;
    var tmp$ret$0;
    $l$block_0: {
      // Inline function 'kotlin.collections.any' call
      var tmp;
      if (isInterface(tmp0, Collection)) {
        tmp = tmp0.isEmpty_y1axqb_k$();
      } else {
        tmp = false;
      }
      if (tmp) {
        tmp$ret$0 = false;
        break $l$block_0;
      }
      var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        if (element.id.equals(node.id)) {
          tmp$ret$0 = true;
          break $l$block_0;
        }
      }
      tmp$ret$0 = false;
    }
    if (tmp$ret$0) {
      return false;
    }
    // Inline function 'kotlin.collections.plusAssign' call
    this.nodes.add_utx5q5_k$(node);
    return true;
  }
  detach(node) {
    return this.nodes.remove_cedx0m_k$(node);
  }
  close() {
    // Inline function 'kotlin.collections.forEach' call
    var _iterator__ex2g4s = toList(this.nodes).iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      element.close();
      this.detach(element);
    }
    this.nodes.clear_j9egeb_k$();
  }
  toString() {
    return "[PortGraph] label='" + this.label + "' id='" + this.id.toString() + "' nodes=" + this.nodes.get_size_woubt6_k$();
  }
  get id() {
    return this.get_id_kntnx8_k$();
  }
  get label() {
    return this.get_label_iuj8p7_k$();
  }
}
class PortCapability {}
function registerProvider(keyType, impl) {
  return registerProvider_0(this, keyType.key, keyType.type, impl);
}
function getProvider(keyType) {
  return getProvider_0(this, keyType.key, keyType.type);
}
function registerConsumer(keyType) {
  return registerConsumer_0(this, keyType.key, keyType.type);
}
function getConsumer(keyType) {
  return getConsumer_0(this, keyType.key, keyType.type);
}
class PortNode {
  constructor(graph, id, label, portCapability) {
    id = id === VOID ? Companion_getInstance_10().random_fimq0t_k$() : id;
    label = label === VOID ? '' : label;
    portCapability = portCapability === VOID ? new PortCapabilityImpl() : portCapability;
    this.$$delegate_0__1 = portCapability;
    this.graph_1 = graph;
    this.id_1 = id;
    this.label_1 = label;
  }
  get_graph_is3411_k$() {
    return this.graph_1;
  }
  get_id_kntnx8_k$() {
    return this.id_1;
  }
  get_label_iuj8p7_k$() {
    return this.label_1;
  }
  close() {
    // Inline function 'dev.shibasis.reaktor.portgraph.port.flattenedValues' call
    // Inline function 'kotlin.collections.flatMap' call
    var tmp0 = this.consumerPorts.get_values_ksazhn_k$();
    // Inline function 'kotlin.collections.flatMapTo' call
    var destination = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
    var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      var list = element.get_values_ksazhn_k$();
      addAll(destination, list);
    }
    // Inline function 'kotlin.collections.forEach' call
    var _iterator__ex2g4s_0 = destination.iterator_jk1svi_k$();
    while (_iterator__ex2g4s_0.hasNext_bitz1p_k$()) {
      var element_0 = _iterator__ex2g4s_0.next_20eer_k$();
      element_0.close_yn9xrc_k$();
    }
    // Inline function 'dev.shibasis.reaktor.portgraph.port.flattenedValues' call
    // Inline function 'kotlin.collections.flatMap' call
    var tmp0_0 = this.providerPorts.get_values_ksazhn_k$();
    // Inline function 'kotlin.collections.flatMapTo' call
    var destination_0 = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
    var _iterator__ex2g4s_1 = tmp0_0.iterator_jk1svi_k$();
    while (_iterator__ex2g4s_1.hasNext_bitz1p_k$()) {
      var element_1 = _iterator__ex2g4s_1.next_20eer_k$();
      var list_0 = element_1.get_values_ksazhn_k$();
      addAll(destination_0, list_0);
    }
    // Inline function 'kotlin.collections.forEach' call
    var _iterator__ex2g4s_2 = destination_0.iterator_jk1svi_k$();
    while (_iterator__ex2g4s_2.hasNext_bitz1p_k$()) {
      var element_2 = _iterator__ex2g4s_2.next_20eer_k$();
      element_2.close_yn9xrc_k$();
    }
  }
  toString() {
    var tmp = getKClassFromExpression(this).get_simpleName_r6f8py_k$();
    var tmp_0 = this.label;
    var tmp_1 = this.id.toString();
    // Inline function 'dev.shibasis.reaktor.portgraph.port.flattenedValues' call
    // Inline function 'kotlin.collections.flatMap' call
    var tmp0 = this.consumerPorts.get_values_ksazhn_k$();
    // Inline function 'kotlin.collections.flatMapTo' call
    var destination = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
    var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      var list = element.get_values_ksazhn_k$();
      addAll(destination, list);
    }
    var tmp_2 = destination.get_size_woubt6_k$();
    // Inline function 'dev.shibasis.reaktor.portgraph.port.flattenedValues' call
    // Inline function 'kotlin.collections.flatMap' call
    var tmp0_0 = this.providerPorts.get_values_ksazhn_k$();
    // Inline function 'kotlin.collections.flatMapTo' call
    var destination_0 = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
    var _iterator__ex2g4s_0 = tmp0_0.iterator_jk1svi_k$();
    while (_iterator__ex2g4s_0.hasNext_bitz1p_k$()) {
      var element_0 = _iterator__ex2g4s_0.next_20eer_k$();
      var list_0 = element_0.get_values_ksazhn_k$();
      addAll(destination_0, list_0);
    }
    return '[' + tmp + "] label='" + tmp_0 + "' id='" + tmp_1 + "' inputs=" + tmp_2 + ' outputs=' + destination_0.get_size_woubt6_k$();
  }
  get_consumerPorts_66pzsd_k$() {
    return this.$$delegate_0__1.consumerPorts;
  }
  get_providerPorts_3173wo_k$() {
    return this.$$delegate_0__1.providerPorts;
  }
  addPortEventListener(listener) {
    this.$$delegate_0__1.addPortEventListener(listener);
  }
  removePortEventListener(listener) {
    this.$$delegate_0__1.removePortEventListener(listener);
  }
  emit(event) {
    this.$$delegate_0__1.emit(event);
  }
  registerProvider(keyType, impl) {
    return this.$$delegate_0__1.registerProvider(keyType, impl);
  }
  getProvider(keyType) {
    return this.$$delegate_0__1.getProvider(keyType);
  }
  registerConsumer(keyType) {
    return this.$$delegate_0__1.registerConsumer(keyType);
  }
  getConsumer(keyType) {
    return this.$$delegate_0__1.getConsumer(keyType);
  }
  get graph() {
    return this.get_graph_is3411_k$();
  }
  get id() {
    return this.get_id_kntnx8_k$();
  }
  get label() {
    return this.get_label_iuj8p7_k$();
  }
  get consumerPorts() {
    return this.get_consumerPorts_66pzsd_k$();
  }
  get providerPorts() {
    return this.get_providerPorts_3173wo_k$();
  }
}
class Port {
  constructor(owner, key, type) {
    return new.target.new_dev_shibasis_reaktor_portgraph_port_Port_fys60m_k$(owner, key, type);
  }
  static new_dev_shibasis_reaktor_portgraph_port_Port_fys60m_k$(owner, key, type) {
    var $this = createThis(this);
    $this.owner = owner;
    $this.key = key;
    $this.type = type;
    $this.qualifier = 'Port:' + $this.key.toString() + ':' + $this.type.toString();
    return $this;
  }
  get_owner_iwkx3e_k$() {
    return this.owner;
  }
  get_key_18j28a_k$() {
    return this.key;
  }
  get_type_wovaf7_k$() {
    return this.type;
  }
  static createWithStrings(owner, key, type) {
    return this.new_dev_shibasis_reaktor_portgraph_port_Port_fys60m_k$(owner, new Key_0(key), new Type(type));
  }
  toString() {
    var tmp = this.owner;
    var tmp0_safe_receiver = isInterface(tmp, Unique) ? tmp : null;
    var tmp_0;
    if (tmp0_safe_receiver == null) {
      tmp_0 = null;
    } else {
      // Inline function 'kotlin.let' call
      tmp_0 = tmp0_safe_receiver.label + ' (' + tmp0_safe_receiver.id.toString() + ')';
    }
    var tmp1_elvis_lhs = tmp_0;
    var ownerId = tmp1_elvis_lhs == null ? 'Unknown' : tmp1_elvis_lhs;
    return '[' + getKClassFromExpression(this).get_simpleName_r6f8py_k$() + "] key='" + this.key.key + "' type='" + this.type.type + "' owner='" + ownerId + "'";
  }
  get_qualifier_c4gvsv_k$() {
    return this.qualifier;
  }
}
class ConsumerPort extends Port {
  constructor(owner, key, type) {
    return new.target.new_dev_shibasis_reaktor_portgraph_port_ConsumerPort_931x6c_k$(owner, key, type);
  }
  static new_dev_shibasis_reaktor_portgraph_port_ConsumerPort_931x6c_k$(owner, key, type) {
    var $this = this.new_dev_shibasis_reaktor_portgraph_port_Port_fys60m_k$(owner, key, type);
    $this.edge = null;
    return $this;
  }
  set_edge_ajjxw6_k$(_set____db54di) {
    this.edge = _set____db54di;
  }
  get_edge_wol9ty_k$() {
    return this.edge;
  }
  get_impl_woo0o9_k$() {
    var tmp0_safe_receiver = this.edge;
    var tmp1_safe_receiver = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.provider;
    return tmp1_safe_receiver == null ? null : tmp1_safe_receiver.impl;
  }
  isConnected() {
    return !(this.impl == null);
  }
  __guard() {
    // Inline function 'kotlin.require' call
    if (!this.isConnected()) {
      var message = "Can't invoke functions through unconnected ports. " + this.toString();
      throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
    }
  }
  invoke(fn) {
    this.__guard();
    return fn(ensureNotNull(this.impl));
  }
  suspended(fn) {
    return promisify(($completion) => this.suspended$suspendBridge_gn2tjb_k$(fn, $completion));
  }
  suspended$suspendBridge_gn2tjb_k$(fn, $completion) {
    return suspendOrReturn(/*#__NOINLINE__*/_generator_suspended$suspendBridge__ucnhzs_0.bind(VOID, this, fn), $completion);
  }
  suspended_n1fheg_k$(fn, $completion) {
    this.__guard();
    return fn(ensureNotNull(this.impl), $completion);
  }
  close_yn9xrc_k$() {
    var tmp0_safe_receiver = this.edge;
    var tmp1_safe_receiver = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.provider;
    var tmp2_safe_receiver = tmp1_safe_receiver == null ? null : tmp1_safe_receiver.edges;
    if (tmp2_safe_receiver == null)
      null;
    else
      tmp2_safe_receiver.remove_gppy8k_k$(this);
    this.edge = null;
  }
  toString() {
    var tmp;
    if (this.isConnected()) {
      var tmp0_safe_receiver = this.edge;
      tmp = 'Connected -> ' + toString_0(tmp0_safe_receiver == null ? null : tmp0_safe_receiver.id);
    } else {
      tmp = 'Unconnected';
    }
    var connectionState = tmp;
    return super.toString() + ' ' + connectionState;
  }
  get impl() {
    return this.get_impl_woo0o9_k$();
  }
}
class Key_0 {
  constructor(key) {
    this.key = key;
  }
  get_key_18j28a_k$() {
    return this.key;
  }
  component1_7eebsc_k$() {
    return this.key;
  }
  copy_a35qlh_k$(key) {
    return new Key_0(key);
  }
  copy(key, $super) {
    key = key === VOID ? this.key : key;
    return $super === VOID ? this.copy_a35qlh_k$(key) : $super.copy_a35qlh_k$.call(this, key);
  }
  toString() {
    return 'Key(key=' + this.key + ')';
  }
  hashCode() {
    return getStringHashCode(this.key);
  }
  equals(other) {
    if (this === other)
      return true;
    if (!(other instanceof Key_0))
      return false;
    if (!(this.key === other.key))
      return false;
    return true;
  }
}
class Companion_11 {
  create(kClass) {
    return new Type(name(kClass), kClass);
  }
  Type_4artt7_k$(value) {
    return this.create(getKClassFromExpression(value));
  }
}
class Type {
  constructor(type, kClass) {
    kClass = kClass === VOID ? null : kClass;
    this.type = type;
    this.kClass = kClass;
  }
  get_type_wovaf7_k$() {
    return this.type;
  }
  get_kClass_f4awuu_k$() {
    return this.kClass;
  }
  toString() {
    return this.type;
  }
  component1_7eebsc_k$() {
    return this.type;
  }
  component2_7eebsb_k$() {
    return this.kClass;
  }
  copy_a9vm5g_k$(type, kClass) {
    return new Type(type, kClass);
  }
  copy(type, kClass, $super) {
    type = type === VOID ? this.type : type;
    kClass = kClass === VOID ? this.kClass : kClass;
    return $super === VOID ? this.copy_a9vm5g_k$(type, kClass) : $super.copy_a9vm5g_k$.call(this, type, kClass);
  }
  hashCode() {
    var result = getStringHashCode(this.type);
    result = imul_0(result, 31) + (this.kClass == null ? 0 : this.kClass.hashCode()) | 0;
    return result;
  }
  equals(other) {
    if (this === other)
      return true;
    if (!(other instanceof Type))
      return false;
    if (!(this.type === other.type))
      return false;
    if (!equals(this.kClass, other.kClass))
      return false;
    return true;
  }
}
class Companion_12 {
  invoke_h1q7yg_k$(key, type) {
    return new KeyType(new Key_0(key), new Type(type));
  }
}
class KeyType {
  constructor(key, type) {
    this.key = key;
    this.type = type;
  }
  get_key_18j28a_k$() {
    return this.key;
  }
  get_type_wovaf7_k$() {
    return this.type;
  }
  component1_7eebsc_k$() {
    return this.key;
  }
  component2_7eebsb_k$() {
    return this.type;
  }
  copy_u6xdi0_k$(key, type) {
    return new KeyType(key, type);
  }
  copy(key, type, $super) {
    key = key === VOID ? this.key : key;
    type = type === VOID ? this.type : type;
    return $super === VOID ? this.copy_u6xdi0_k$(key, type) : $super.copy_u6xdi0_k$.call(this, key, type);
  }
  toString() {
    return 'KeyType(key=' + this.key.toString() + ', type=' + this.type.toString() + ')';
  }
  hashCode() {
    var result = this.key.hashCode();
    result = imul_0(result, 31) + this.type.hashCode() | 0;
    return result;
  }
  equals(other) {
    if (this === other)
      return true;
    if (!(other instanceof KeyType))
      return false;
    if (!this.key.equals(other.key))
      return false;
    if (!this.type.equals(other.type))
      return false;
    return true;
  }
}
class PortEvent {
  constructor(port) {
    this.port = port;
  }
  get_port_wosj4a_k$() {
    return this.port;
  }
}
class Created extends PortEvent {}
class Connected extends PortEvent {
  constructor(port, other) {
    super(port);
    this.other = other;
  }
  get_other_iwivon_k$() {
    return this.other;
  }
}
class Disconnected extends PortEvent {
  constructor(port, other) {
    super(port);
    this.other = other;
  }
  get_other_iwivon_k$() {
    return this.other;
  }
}
class PortCapabilityImpl {
  constructor(consumerPorts, providerPorts, listeners) {
    var tmp;
    if (consumerPorts === VOID) {
      // Inline function 'kotlin.collections.hashMapOf' call
      tmp = HashMap.new_kotlin_collections_HashMap_2a5kxx_k$();
    } else {
      tmp = consumerPorts;
    }
    consumerPorts = tmp;
    var tmp_0;
    if (providerPorts === VOID) {
      // Inline function 'kotlin.collections.hashMapOf' call
      tmp_0 = HashMap.new_kotlin_collections_HashMap_2a5kxx_k$();
    } else {
      tmp_0 = providerPorts;
    }
    providerPorts = tmp_0;
    var tmp_1;
    if (listeners === VOID) {
      // Inline function 'kotlin.collections.mutableListOf' call
      tmp_1 = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
    } else {
      tmp_1 = listeners;
    }
    listeners = tmp_1;
    this.consumerPorts_1 = consumerPorts;
    this.providerPorts_1 = providerPorts;
    this.listeners_1 = listeners;
  }
  get_consumerPorts_66pzsd_k$() {
    return this.consumerPorts_1;
  }
  get_providerPorts_3173wo_k$() {
    return this.providerPorts_1;
  }
  addPortEventListener(listener) {
    this.listeners_1.add_utx5q5_k$(listener);
  }
  removePortEventListener(listener) {
    this.listeners_1.remove_cedx0m_k$(listener);
  }
  emit(event) {
    // Inline function 'kotlin.collections.forEach' call
    var _iterator__ex2g4s = this.listeners_1.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      element(event);
    }
  }
  get consumerPorts() {
    return this.get_consumerPorts_66pzsd_k$();
  }
  get providerPorts() {
    return this.get_providerPorts_3173wo_k$();
  }
}
class ProviderPort extends Port {
  constructor(owner, key, type, impl, edges) {
    return new.target.new_dev_shibasis_reaktor_portgraph_port_ProviderPort_l7jdif_k$(owner, key, type, impl, edges);
  }
  static new_dev_shibasis_reaktor_portgraph_port_ProviderPort_l7jdif_k$(owner, key, type, impl, edges) {
    var tmp;
    if (edges === VOID) {
      // Inline function 'kotlin.collections.linkedMapOf' call
      tmp = LinkedHashMap.new_kotlin_collections_LinkedHashMap_ga0any_k$();
    } else {
      tmp = edges;
    }
    edges = tmp;
    var $this = this.new_dev_shibasis_reaktor_portgraph_port_Port_fys60m_k$(owner, key, type);
    $this.impl = impl;
    $this.edges = edges;
    return $this;
  }
  get_impl_woo0o9_k$() {
    return this.impl;
  }
  get_edges_iqqp7x_k$() {
    return this.edges;
  }
  static create(owner, key, impl) {
    return this.new_dev_shibasis_reaktor_portgraph_port_ProviderPort_l7jdif_k$(owner, new Key_0(key), Companion_instance_11.Type_4artt7_k$(impl), impl);
  }
  isConnected() {
    // Inline function 'kotlin.collections.isNotEmpty' call
    return !this.edges.isEmpty_y1axqb_k$();
  }
  invoke(fn) {
    return fn(this.impl);
  }
  suspended(fn) {
    return promisify(($completion) => this.suspended$suspendBridge_gn2tjb_k$(fn, $completion));
  }
  suspended$suspendBridge_gn2tjb_k$(fn, $completion) {
    return suspendOrReturn(/*#__NOINLINE__*/_generator_suspended$suspendBridge__ucnhzs_1.bind(VOID, this, fn), $completion);
  }
  suspended_n1fheg_k$(fn, $completion) {
    return fn(this.impl, $completion);
  }
  close_yn9xrc_k$() {
    // Inline function 'kotlin.collections.forEach' call
    var _iterator__ex2g4s = this.edges.get_keys_wop4xp_k$().iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      element.edge = null;
    }
    this.edges.clear_j9egeb_k$();
  }
  toString() {
    return super.toString() + ' Consumers=' + this.edges.get_size_woubt6_k$();
  }
}
class Selector {}
class StructuralSelector_0 {
  neighbors(visitable) {
    var tmp;
    if (visitable instanceof PortGraph) {
      tmp = visitable.nodes;
    } else {
      if (visitable instanceof PortNode) {
        // Inline function 'kotlin.collections.buildList' call
        // Inline function 'kotlin.collections.buildListInternal' call
        // Inline function 'kotlin.apply' call
        var this_0 = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
        // Inline function 'dev.shibasis.reaktor.portgraph.port.flattenedValues' call
        // Inline function 'kotlin.collections.flatMap' call
        var tmp0 = visitable.providerPorts.get_values_ksazhn_k$();
        // Inline function 'kotlin.collections.flatMapTo' call
        var destination = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
        var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
        while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
          var element = _iterator__ex2g4s.next_20eer_k$();
          var list = element.get_values_ksazhn_k$();
          addAll(destination, list);
        }
        this_0.addAll_h3ej1q_k$(destination);
        // Inline function 'dev.shibasis.reaktor.portgraph.port.flattenedValues' call
        // Inline function 'kotlin.collections.flatMap' call
        var tmp0_0 = visitable.consumerPorts.get_values_ksazhn_k$();
        // Inline function 'kotlin.collections.flatMapTo' call
        var destination_0 = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
        var _iterator__ex2g4s_0 = tmp0_0.iterator_jk1svi_k$();
        while (_iterator__ex2g4s_0.hasNext_bitz1p_k$()) {
          var element_0 = _iterator__ex2g4s_0.next_20eer_k$();
          var list_0 = element_0.get_values_ksazhn_k$();
          addAll(destination_0, list_0);
        }
        this_0.addAll_h3ej1q_k$(destination_0);
        tmp = this_0.build_nmwvly_k$();
      } else {
        if (visitable instanceof ConsumerPort) {
          var tmp1_safe_receiver = visitable.edge;
          var tmp_0;
          if (tmp1_safe_receiver == null) {
            tmp_0 = null;
          } else {
            // Inline function 'kotlin.let' call
            tmp_0 = listOf(tmp1_safe_receiver);
          }
          var tmp2_elvis_lhs = tmp_0;
          var tmp_1;
          if (tmp2_elvis_lhs == null) {
            // Inline function 'kotlin.collections.listOf' call
            tmp_1 = emptyList();
          } else {
            tmp_1 = tmp2_elvis_lhs;
          }
          tmp = tmp_1;
        } else {
          if (visitable instanceof ProviderPort) {
            tmp = toList(visitable.edges.get_values_ksazhn_k$());
          } else {
            if (visitable instanceof Edge) {
              // Inline function 'kotlin.collections.listOf' call
              tmp = emptyList();
            } else {
              tmp = emptyList();
            }
          }
        }
      }
    }
    return tmp;
  }
}
class ConnectivitySelector_0 {
  neighbors(visitable) {
    var tmp;
    if (visitable instanceof PortNode) {
      // Inline function 'dev.shibasis.reaktor.portgraph.port.flattenedValues' call
      // Inline function 'kotlin.collections.flatMap' call
      var tmp0 = visitable.consumerPorts.get_values_ksazhn_k$();
      // Inline function 'kotlin.collections.flatMapTo' call
      var destination = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
      var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        var list = element.get_values_ksazhn_k$();
        addAll(destination, list);
      }
      // Inline function 'kotlin.collections.mapNotNull' call
      // Inline function 'kotlin.collections.mapNotNullTo' call
      var destination_0 = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
      // Inline function 'kotlin.collections.forEach' call
      var _iterator__ex2g4s_0 = destination.iterator_jk1svi_k$();
      while (_iterator__ex2g4s_0.hasNext_bitz1p_k$()) {
        var element_0 = _iterator__ex2g4s_0.next_20eer_k$();
        var tmp$ret$4;
        $l$block: {
          var tmp0_elvis_lhs = element_0.edge;
          var tmp_0;
          if (tmp0_elvis_lhs == null) {
            tmp$ret$4 = null;
            break $l$block;
          } else {
            tmp_0 = tmp0_elvis_lhs;
          }
          var edge = tmp_0;
          tmp$ret$4 = equals(edge.source, visitable) ? edge.destination : edge.source;
        }
        var tmp0_safe_receiver = tmp$ret$4;
        if (tmp0_safe_receiver == null)
          null;
        else {
          // Inline function 'kotlin.let' call
          destination_0.add_utx5q5_k$(tmp0_safe_receiver);
        }
      }
      // Inline function 'kotlin.collections.map' call
      // Inline function 'kotlin.collections.mapTo' call
      var destination_1 = ArrayList.new_kotlin_collections_ArrayList_tdd6ob_k$(collectionSizeOrDefault(destination_0, 10));
      var _iterator__ex2g4s_1 = destination_0.iterator_jk1svi_k$();
      while (_iterator__ex2g4s_1.hasNext_bitz1p_k$()) {
        var item = _iterator__ex2g4s_1.next_20eer_k$();
        var tmp$ret$11 = isInterface(item, Visitable) ? item : THROW_CCE();
        destination_1.add_utx5q5_k$(tmp$ret$11);
      }
      tmp = destination_1;
    } else {
      tmp = emptyList();
    }
    return tmp;
  }
}
class Traverser {}
class DepthFirstTraverser_0 {
  traverse(start, selector, visitor) {
    // Inline function 'kotlin.collections.mutableSetOf' call
    var tmp$ret$0 = LinkedHashSet.new_kotlin_collections_LinkedHashSet_ahyf7j_k$();
    traverseRecursive(this, start, selector, visitor, tmp$ret$0);
  }
}
class BreadthFirstTraverser_0 {
  traverse(start, selector, visitor) {
    // Inline function 'kotlin.collections.mutableSetOf' call
    var visited = LinkedHashSet.new_kotlin_collections_LinkedHashSet_ahyf7j_k$();
    var queue = ArrayDeque.new_kotlin_collections_ArrayDeque_sf0swv_k$();
    queue.add_utx5q5_k$(start);
    $l$loop_0: while (true) {
      // Inline function 'kotlin.collections.isNotEmpty' call
      if (!!queue.isEmpty_y1axqb_k$()) {
        break $l$loop_0;
      }
      var current = queue.removeFirst_58pi0k_k$();
      if (!visited.add_utx5q5_k$(current))
        continue $l$loop_0;
      visitor.visit(current);
      // Inline function 'kotlin.collections.forEach' call
      var _iterator__ex2g4s = selector.neighbors(current).iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        if (!visited.contains_aljjnj_k$(element)) {
          queue.add_utx5q5_k$(element);
        }
      }
    }
  }
}
class PortGraphVisitor {
  constructor() {
    var tmp = this;
    tmp.NoOpExit = PortGraphVisitor$NoOpExit$lambda;
  }
  get_NoOpExit_lxykif_k$() {
    return this.NoOpExit;
  }
  visit(visitable) {
    var tmp;
    if (visitable instanceof PortGraph) {
      tmp = this.visitGraph(visitable);
    } else {
      if (visitable instanceof PortNode) {
        tmp = this.visitNode(visitable);
      } else {
        if (visitable instanceof ConsumerPort) {
          tmp = this.visitConsumerPort(visitable);
        } else {
          if (visitable instanceof ProviderPort) {
            tmp = this.visitProviderPort(visitable);
          } else {
            if (visitable instanceof Edge) {
              tmp = this.visitEdge(visitable);
            } else {
              tmp = this.NoOpExit;
            }
          }
        }
      }
    }
    return tmp;
  }
  visitGraph(graph) {
    return this.NoOpExit;
  }
  visitNode(node) {
    return this.NoOpExit;
  }
  visitConsumerPort(port) {
    return this.NoOpExit;
  }
  visitProviderPort(port) {
    return this.NoOpExit;
  }
  visitEdge(edge) {
    return this.NoOpExit;
  }
}
class HierarchyVisitor extends PortGraphVisitor {
  constructor() {
    super();
    this.mapStack_1 = ArrayDeque.new_kotlin_collections_ArrayDeque_sf0swv_k$();
  }
  set_rootMap_l44r76_k$(_set____db54di) {
    this.rootMap_1 = _set____db54di;
  }
  get_rootMap_o37xo1_k$() {
    var tmp = this.rootMap_1;
    if (!(tmp == null))
      return tmp;
    else {
      throwUninitializedPropertyAccessException('rootMap');
    }
  }
  visitGraph(graph) {
    return processVisit(this, graph);
  }
  visitNode(node) {
    return processVisit(this, node);
  }
  visitConsumerPort(port) {
    return processVisit(this, port);
  }
  visitProviderPort(port) {
    return processVisit(this, port);
  }
  visitEdge(edge) {
    return processVisit(this, edge);
  }
  get rootMap() {
    return this.get_rootMap_o37xo1_k$();
  }
  set rootMap(value) {
    this.set_rootMap_l44r76_k$(value);
  }
}
//endregion
function throwUninitializedPropertyAccessException(name) {
  throw UninitializedPropertyAccessException.new_kotlin_UninitializedPropertyAccessException_egi92l_k$('lateinit property ' + name + ' has not been initialized');
}
function joinToString(_this__u8e3s4, separator, prefix, postfix, limit, truncated, transform) {
  separator = separator === VOID ? ', ' : separator;
  prefix = prefix === VOID ? '' : prefix;
  postfix = postfix === VOID ? '' : postfix;
  limit = limit === VOID ? -1 : limit;
  truncated = truncated === VOID ? '...' : truncated;
  transform = transform === VOID ? null : transform;
  return joinTo(_this__u8e3s4, StringBuilder.new_kotlin_text_StringBuilder_u46mrb_k$(), separator, prefix, postfix, limit, truncated, transform).toString();
}
function indexOf(_this__u8e3s4, element) {
  if (element == null) {
    var inductionVariable = 0;
    var last = _this__u8e3s4.length - 1 | 0;
    if (inductionVariable <= last)
      do {
        var index = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        if (_this__u8e3s4[index] == null) {
          return index;
        }
      }
       while (inductionVariable <= last);
  } else {
    var inductionVariable_0 = 0;
    var last_0 = _this__u8e3s4.length - 1 | 0;
    if (inductionVariable_0 <= last_0)
      do {
        var index_0 = inductionVariable_0;
        inductionVariable_0 = inductionVariable_0 + 1 | 0;
        if (equals(element, _this__u8e3s4[index_0])) {
          return index_0;
        }
      }
       while (inductionVariable_0 <= last_0);
  }
  return -1;
}
function lastIndexOf(_this__u8e3s4, element) {
  if (element == null) {
    var inductionVariable = _this__u8e3s4.length - 1 | 0;
    if (0 <= inductionVariable)
      do {
        var index = inductionVariable;
        inductionVariable = inductionVariable + -1 | 0;
        if (_this__u8e3s4[index] == null) {
          return index;
        }
      }
       while (0 <= inductionVariable);
  } else {
    var inductionVariable_0 = _this__u8e3s4.length - 1 | 0;
    if (0 <= inductionVariable_0)
      do {
        var index_0 = inductionVariable_0;
        inductionVariable_0 = inductionVariable_0 + -1 | 0;
        if (equals(element, _this__u8e3s4[index_0])) {
          return index_0;
        }
      }
       while (0 <= inductionVariable_0);
  }
  return -1;
}
function get_lastIndex(_this__u8e3s4) {
  return _this__u8e3s4.length - 1 | 0;
}
function joinTo(_this__u8e3s4, buffer, separator, prefix, postfix, limit, truncated, transform) {
  separator = separator === VOID ? ', ' : separator;
  prefix = prefix === VOID ? '' : prefix;
  postfix = postfix === VOID ? '' : postfix;
  limit = limit === VOID ? -1 : limit;
  truncated = truncated === VOID ? '...' : truncated;
  transform = transform === VOID ? null : transform;
  buffer.append_jgojdo_k$(prefix);
  var count = 0;
  var inductionVariable = 0;
  var last = _this__u8e3s4.length;
  $l$loop: while (inductionVariable < last) {
    var element = _this__u8e3s4[inductionVariable];
    inductionVariable = inductionVariable + 1 | 0;
    count = count + 1 | 0;
    if (count > 1) {
      buffer.append_jgojdo_k$(separator);
    }
    if (limit < 0 || count <= limit) {
      if (!(transform == null))
        buffer.append_jgojdo_k$(transform(element));
      else
        buffer.append_jgojdo_k$(element.toString());
    } else
      break $l$loop;
  }
  if (limit >= 0 && count > limit) {
    buffer.append_jgojdo_k$(truncated);
  }
  buffer.append_jgojdo_k$(postfix);
  return buffer;
}
function joinToString_0(_this__u8e3s4, separator, prefix, postfix, limit, truncated, transform) {
  separator = separator === VOID ? ', ' : separator;
  prefix = prefix === VOID ? '' : prefix;
  postfix = postfix === VOID ? '' : postfix;
  limit = limit === VOID ? -1 : limit;
  truncated = truncated === VOID ? '...' : truncated;
  transform = transform === VOID ? null : transform;
  return joinTo_0(_this__u8e3s4, StringBuilder.new_kotlin_text_StringBuilder_u46mrb_k$(), separator, prefix, postfix, limit, truncated, transform).toString();
}
function joinTo_0(_this__u8e3s4, buffer, separator, prefix, postfix, limit, truncated, transform) {
  separator = separator === VOID ? ', ' : separator;
  prefix = prefix === VOID ? '' : prefix;
  postfix = postfix === VOID ? '' : postfix;
  limit = limit === VOID ? -1 : limit;
  truncated = truncated === VOID ? '...' : truncated;
  transform = transform === VOID ? null : transform;
  buffer.append_jgojdo_k$(prefix);
  var count = 0;
  var inductionVariable = 0;
  var last = _this__u8e3s4.length;
  $l$loop: while (inductionVariable < last) {
    var element = _this__u8e3s4[inductionVariable];
    inductionVariable = inductionVariable + 1 | 0;
    count = count + 1 | 0;
    if (count > 1) {
      buffer.append_jgojdo_k$(separator);
    }
    if (limit < 0 || count <= limit) {
      appendElement(buffer, element, transform);
    } else
      break $l$loop;
  }
  if (limit >= 0 && count > limit) {
    buffer.append_jgojdo_k$(truncated);
  }
  buffer.append_jgojdo_k$(postfix);
  return buffer;
}
function joinToString_1(_this__u8e3s4, separator, prefix, postfix, limit, truncated, transform) {
  separator = separator === VOID ? ', ' : separator;
  prefix = prefix === VOID ? '' : prefix;
  postfix = postfix === VOID ? '' : postfix;
  limit = limit === VOID ? -1 : limit;
  truncated = truncated === VOID ? '...' : truncated;
  transform = transform === VOID ? null : transform;
  return joinTo_1(_this__u8e3s4, StringBuilder.new_kotlin_text_StringBuilder_u46mrb_k$(), separator, prefix, postfix, limit, truncated, transform).toString();
}
function joinTo_1(_this__u8e3s4, buffer, separator, prefix, postfix, limit, truncated, transform) {
  separator = separator === VOID ? ', ' : separator;
  prefix = prefix === VOID ? '' : prefix;
  postfix = postfix === VOID ? '' : postfix;
  limit = limit === VOID ? -1 : limit;
  truncated = truncated === VOID ? '...' : truncated;
  transform = transform === VOID ? null : transform;
  buffer.append_jgojdo_k$(prefix);
  var count = 0;
  var _iterator__ex2g4s = _this__u8e3s4.iterator_jk1svi_k$();
  $l$loop: while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var element = _iterator__ex2g4s.next_20eer_k$();
    count = count + 1 | 0;
    if (count > 1) {
      buffer.append_jgojdo_k$(separator);
    }
    if (limit < 0 || count <= limit) {
      appendElement(buffer, element, transform);
    } else
      break $l$loop;
  }
  if (limit >= 0 && count > limit) {
    buffer.append_jgojdo_k$(truncated);
  }
  buffer.append_jgojdo_k$(postfix);
  return buffer;
}
function toList(_this__u8e3s4) {
  if (isInterface(_this__u8e3s4, Collection)) {
    var tmp;
    switch (_this__u8e3s4.get_size_woubt6_k$()) {
      case 0:
        tmp = emptyList();
        break;
      case 1:
        var tmp_0;
        if (isInterface(_this__u8e3s4, KtList)) {
          tmp_0 = _this__u8e3s4.get_c1px32_k$(0);
        } else {
          tmp_0 = _this__u8e3s4.iterator_jk1svi_k$().next_20eer_k$();
        }

        tmp = listOf(tmp_0);
        break;
      default:
        tmp = toMutableList(_this__u8e3s4);
        break;
    }
    return tmp;
  }
  return optimizeReadOnlyList(toMutableList_0(_this__u8e3s4));
}
function first(_this__u8e3s4) {
  if (isInterface(_this__u8e3s4, KtList))
    return first_0(_this__u8e3s4);
  else {
    var iterator = _this__u8e3s4.iterator_jk1svi_k$();
    if (!iterator.hasNext_bitz1p_k$())
      throw NoSuchElementException.new_kotlin_NoSuchElementException_eborbh_k$('Collection is empty.');
    return iterator.next_20eer_k$();
  }
}
function intersect(_this__u8e3s4, other) {
  var otherCollection = convertToListIfNotCollection(other);
  // Inline function 'kotlin.collections.mutableSetOf' call
  var set = LinkedHashSet.new_kotlin_collections_LinkedHashSet_ahyf7j_k$();
  var _iterator__ex2g4s = _this__u8e3s4.iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var e = _iterator__ex2g4s.next_20eer_k$();
    if (otherCollection.contains_aljjnj_k$(e)) {
      set.add_utx5q5_k$(e);
    }
  }
  return set;
}
function toMutableList(_this__u8e3s4) {
  return ArrayList.new_kotlin_collections_ArrayList_nk3udn_k$(_this__u8e3s4);
}
function toMutableList_0(_this__u8e3s4) {
  if (isInterface(_this__u8e3s4, Collection))
    return toMutableList(_this__u8e3s4);
  return toCollection(_this__u8e3s4, ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$());
}
function first_0(_this__u8e3s4) {
  if (_this__u8e3s4.isEmpty_y1axqb_k$())
    throw NoSuchElementException.new_kotlin_NoSuchElementException_eborbh_k$('List is empty.');
  return _this__u8e3s4.get_c1px32_k$(0);
}
function toCollection(_this__u8e3s4, destination) {
  var _iterator__ex2g4s = _this__u8e3s4.iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var item = _iterator__ex2g4s.next_20eer_k$();
    destination.add_utx5q5_k$(item);
  }
  return destination;
}
function coerceAtLeast(_this__u8e3s4, minimumValue) {
  return _this__u8e3s4 < minimumValue ? minimumValue : _this__u8e3s4;
}
function coerceAtMost(_this__u8e3s4, maximumValue) {
  return _this__u8e3s4 > maximumValue ? maximumValue : _this__u8e3s4;
}
function take(_this__u8e3s4, n) {
  // Inline function 'kotlin.require' call
  if (!(n >= 0)) {
    var message = 'Requested character count ' + n + ' is less than zero.';
    throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
  }
  return substring(_this__u8e3s4, 0, coerceAtMost(n, _this__u8e3s4.length));
}
function _Char___init__impl__6a9atx(value) {
  return value;
}
function _get_value__a43j40($this) {
  return $this;
}
function _Char___init__impl__6a9atx_0(code) {
  // Inline function 'kotlin.UShort.toInt' call
  var tmp$ret$0 = _UShort___get_data__impl__g0245(code) & 65535;
  return _Char___init__impl__6a9atx(tmp$ret$0);
}
function Char__compareTo_impl_ypi4mb($this, other) {
  return _get_value__a43j40($this) - _get_value__a43j40(other) | 0;
}
function Char__toInt_impl_vasixd($this) {
  return _get_value__a43j40($this);
}
function toString($this) {
  // Inline function 'kotlin.js.unsafeCast' call
  return String.fromCharCode(_get_value__a43j40($this));
}
var Companion_instance;
function Companion_getInstance() {
  return Companion_instance;
}
function fromJsArray(array) {
  return Companion_instance.fromJsArray_n3u761_k$(array);
}
var Companion_instance_0;
function Companion_getInstance_0() {
  return Companion_instance_0;
}
function fromJsMap(map) {
  return Companion_instance_0.fromJsMap_p3spvk_k$(map);
}
var Companion_instance_1;
function Companion_getInstance_1() {
  return Companion_instance_1;
}
function fromJsArray_0(array) {
  return Companion_instance_1.fromJsArray_n3u761_k$(array);
}
var Companion_instance_2;
function Companion_getInstance_2() {
  return Companion_instance_2;
}
function toString_0(_this__u8e3s4) {
  var tmp1_elvis_lhs = _this__u8e3s4 == null ? null : toString_1(_this__u8e3s4);
  return tmp1_elvis_lhs == null ? 'null' : tmp1_elvis_lhs;
}
function abs(_this__u8e3s4) {
  var tmp;
  // Inline function 'kotlin.js.internal.isNegative' call
  if (_this__u8e3s4 < 0) {
    // Inline function 'kotlin.js.internal.unaryMinus' call
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    tmp = -_this__u8e3s4;
  } else {
    tmp = _this__u8e3s4;
  }
  return tmp;
}
function implement(interfaces) {
  var maxSize = 1;
  var masks = [];
  var inductionVariable = 0;
  var last = interfaces.length;
  while (inductionVariable < last) {
    var i = interfaces[inductionVariable];
    inductionVariable = inductionVariable + 1 | 0;
    var currentSize = maxSize;
    var tmp0_elvis_lhs = i.prototype.$imask$;
    var imask = tmp0_elvis_lhs == null ? i.$imask$ : tmp0_elvis_lhs;
    if (!(imask == null)) {
      masks.push(imask);
      currentSize = imask.length;
    }
    var iid = i.$metadata$.iid;
    var tmp;
    if (iid == null) {
      tmp = null;
    } else {
      // Inline function 'kotlin.let' call
      tmp = bitMaskWith(iid);
    }
    var iidImask = tmp;
    if (!(iidImask == null)) {
      masks.push(iidImask);
      currentSize = Math.max(currentSize, iidImask.length);
    }
    if (currentSize > maxSize) {
      maxSize = currentSize;
    }
  }
  return compositeBitMask(maxSize, masks);
}
function bitMaskWith(activeBit) {
  var numberIndex = activeBit >> 5;
  var intArray = new Int32Array(numberIndex + 1 | 0);
  var positionInNumber = activeBit & 31;
  var numberWithSettledBit = 1 << positionInNumber;
  intArray[numberIndex] = intArray[numberIndex] | numberWithSettledBit;
  return intArray;
}
function compositeBitMask(capacity, masks) {
  var tmp = 0;
  var tmp_0 = new Int32Array(capacity);
  while (tmp < capacity) {
    var tmp_1 = tmp;
    var result = 0;
    var inductionVariable = 0;
    var last = masks.length;
    while (inductionVariable < last) {
      var mask = masks[inductionVariable];
      inductionVariable = inductionVariable + 1 | 0;
      if (tmp_1 < mask.length) {
        result = result | mask[tmp_1];
      }
    }
    tmp_0[tmp_1] = result;
    tmp = tmp + 1 | 0;
  }
  return tmp_0;
}
function isBitSet(_this__u8e3s4, possibleActiveBit) {
  var numberIndex = possibleActiveBit >> 5;
  if (numberIndex > _this__u8e3s4.length)
    return false;
  var positionInNumber = possibleActiveBit & 31;
  var numberWithSettledBit = 1 << positionInNumber;
  return !((_this__u8e3s4[numberIndex] & numberWithSettledBit) === 0);
}
function get_buf() {
  _init_properties_bitUtils_kt__nfcg4k();
  return buf;
}
var buf;
function get_bufFloat64() {
  _init_properties_bitUtils_kt__nfcg4k();
  return bufFloat64;
}
var bufFloat64;
var bufFloat32;
function get_bufInt32() {
  _init_properties_bitUtils_kt__nfcg4k();
  return bufInt32;
}
var bufInt32;
function get_lowIndex() {
  _init_properties_bitUtils_kt__nfcg4k();
  return lowIndex;
}
var lowIndex;
function get_highIndex() {
  _init_properties_bitUtils_kt__nfcg4k();
  return highIndex;
}
var highIndex;
function getNumberHashCode(obj) {
  _init_properties_bitUtils_kt__nfcg4k();
  // Inline function 'kotlin.js.jsBitwiseOr' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  if ((obj | 0) === obj) {
    return numberToInt(obj);
  }
  get_bufFloat64()[0] = obj;
  return imul_0(get_bufInt32()[get_highIndex()], 31) + get_bufInt32()[get_lowIndex()] | 0;
}
var properties_initialized_bitUtils_kt_i2bo3e;
function _init_properties_bitUtils_kt__nfcg4k() {
  if (!properties_initialized_bitUtils_kt_i2bo3e) {
    properties_initialized_bitUtils_kt_i2bo3e = true;
    buf = new ArrayBuffer(8);
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    bufFloat64 = new Float64Array(get_buf());
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    bufFloat32 = new Float32Array(get_buf());
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    bufInt32 = new Int32Array(get_buf());
    // Inline function 'kotlin.run' call
    get_bufFloat64()[0] = -1.0;
    lowIndex = !(get_bufInt32()[0] === 0) ? 1 : 0;
    highIndex = 1 - get_lowIndex() | 0;
  }
}
function charSequenceGet(a, index) {
  var tmp;
  if (isString(a)) {
    tmp = charCodeAt(a, index);
  } else {
    tmp = a.get_kdzpvg_k$(index);
  }
  return tmp;
}
function isString(a) {
  return typeof a === 'string';
}
function charCodeAt(_this__u8e3s4, index) {
  // Inline function 'kotlin.js.asDynamic' call
  return _this__u8e3s4.charCodeAt(index);
}
function charSequenceLength(a) {
  var tmp;
  if (isString(a)) {
    // Inline function 'kotlin.js.asDynamic' call
    // Inline function 'kotlin.js.unsafeCast' call
    tmp = a.length;
  } else {
    tmp = a.get_length_g42xv3_k$();
  }
  return tmp;
}
function arrayToString(array) {
  return joinToString_0(array, ', ', '[', ']', VOID, VOID, arrayToString$lambda);
}
function arrayToString$lambda(it) {
  return toString_1(it);
}
function createJsReadonlyArrayViewFrom(list) {
  var tmp = createJsReadonlyArrayViewFrom$lambda(list);
  var tmp_0 = createJsReadonlyArrayViewFrom$lambda_0(list);
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_1 = UNSUPPORTED_OPERATION$ref();
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_2 = UNSUPPORTED_OPERATION$ref_0();
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$2 = UNSUPPORTED_OPERATION$ref_1();
  return createJsArrayViewWith(tmp, tmp_0, tmp_1, tmp_2, tmp$ret$2);
}
function createJsArrayViewWith(listSize, listGet, listSet, listDecreaseSize, listIncreaseSize) {
  var arrayView = new Array();
  var tmp = Object;
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$0 = JsArrayView;
  tmp.setPrototypeOf(arrayView, tmp$ret$0.prototype);
  return new Proxy(arrayView, {get: function (target, prop, receiver) {
    if (prop === 'length')
      return listSize();
    var type = typeof prop;
    var index = type === 'string' || type === 'number' ? +prop : undefined;
    if (!isNaN(index))
      return listGet(index);
    return target[prop];
  }, has: function (target, key) {
    return !isNaN(key) && key < listSize();
  }, set: function (obj, prop, value) {
    if (prop === 'length') {
      var size = listSize();
      var newSize = type === 'string' || type === 'number' ? +prop : undefined;
      if (isNaN(newSize))
        throw new RangeError('invalid array length');
      if (newSize < size)
        listDecreaseSize(size - newSize);
      else
        listIncreaseSize(newSize - size);
      return true;
    }
    var type = typeof prop;
    var index = type === 'string' || type === 'number' ? +prop : undefined;
    if (isNaN(index))
      return false;
    listSet(index, value);
    return true;
  }});
}
function UNSUPPORTED_OPERATION() {
  throw UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$();
}
function createJsReadonlyMapViewFrom(map) {
  var tmp = createJsReadonlyMapViewFrom$lambda(map);
  var tmp_0 = createJsReadonlyMapViewFrom$lambda_0(map);
  var tmp_1 = createJsReadonlyMapViewFrom$lambda_1(map);
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_2 = UNSUPPORTED_OPERATION$ref_2();
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_3 = UNSUPPORTED_OPERATION$ref_3();
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_4 = UNSUPPORTED_OPERATION$ref_4();
  var tmp_5 = createJsReadonlyMapViewFrom$lambda_2(map);
  var tmp_6 = createJsReadonlyMapViewFrom$lambda_3(map);
  var tmp_7 = createJsReadonlyMapViewFrom$lambda_4(map);
  return createJsMapViewWith(tmp, tmp_0, tmp_1, tmp_2, tmp_3, tmp_4, tmp_5, tmp_6, tmp_7, createJsReadonlyMapViewFrom$lambda_5);
}
function createJsMapViewFrom(map) {
  var tmp = createJsMapViewFrom$lambda(map);
  var tmp_0 = createJsMapViewFrom$lambda_0(map);
  var tmp_1 = createJsMapViewFrom$lambda_1(map);
  var tmp_2 = createJsMapViewFrom$lambda_2(map);
  var tmp_3 = createJsMapViewFrom$lambda_3(map);
  var tmp_4 = createJsMapViewFrom$lambda_4(map);
  var tmp_5 = createJsMapViewFrom$lambda_5(map);
  var tmp_6 = createJsMapViewFrom$lambda_6(map);
  var tmp_7 = createJsMapViewFrom$lambda_7(map);
  return createJsMapViewWith(tmp, tmp_0, tmp_1, tmp_2, tmp_3, tmp_4, tmp_5, tmp_6, tmp_7, createJsMapViewFrom$lambda_8);
}
function createJsArrayViewFrom(list) {
  var tmp = createJsArrayViewFrom$lambda(list);
  var tmp_0 = createJsArrayViewFrom$lambda_0(list);
  var tmp_1 = createJsArrayViewFrom$lambda_1(list);
  var tmp_2 = createJsArrayViewFrom$lambda_2(list);
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$0 = UNSUPPORTED_OPERATION$ref_5();
  return createJsArrayViewWith(tmp, tmp_0, tmp_1, tmp_2, tmp$ret$0);
}
function createJsMapViewWith(mapSize, mapGet, mapContains, mapPut, mapRemove, mapClear, keysIterator, valuesIterator, entriesIterator, forEach) {
  // Inline function 'kotlin.also' call
  var this_0 = objectCreate(protoOf(JsMapView));
  this_0[Symbol.iterator] = entriesIterator;
  defineProp(this_0, 'size', mapSize, VOID, true);
  var mapView = this_0;
  return Object.assign(mapView, {get: mapGet, set: function (key, value) {
    mapPut(key, value);
    return this;
  }, 'delete': mapRemove, clear: mapClear, has: mapContains, keys: keysIterator, values: valuesIterator, entries: entriesIterator, forEach: function (cb, thisArg) {
    forEach(cb, mapView, thisArg);
  }});
}
function createJsIteratorFrom(iterator, transform) {
  var tmp;
  if (transform === VOID) {
    tmp = createJsIteratorFrom$lambda;
  } else {
    tmp = transform;
  }
  transform = tmp;
  var iteratorNext = createJsIteratorFrom$lambda_0(iterator);
  var iteratorHasNext = createJsIteratorFrom$lambda_1(iterator);
  var jsIterator = {next: function () {
    var result = {done: !iteratorHasNext()};
    if (!result.done)
      result.value = transform(iteratorNext());
    return result;
  }};
  jsIterator[Symbol.iterator] = function () {
    return this;
  };
  return jsIterator;
}
function forEach(cb, collection, thisArg) {
  thisArg = thisArg === VOID ? undefined : thisArg;
  var iterator = collection.entries();
  var result = iterator.next();
  while (!result.done) {
    var value = result.value;
    // Inline function 'kotlin.js.asDynamic' call
    cb.call(thisArg, value[1], value[0], collection);
    result = iterator.next();
  }
}
function createListFrom(array) {
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.unsafeCast' call
  var tmp$ret$1 = array.slice();
  return ArrayList.new_kotlin_collections_ArrayList_qfbsh5_k$(tmp$ret$1).build_nmwvly_k$();
}
function createMutableListFrom(array) {
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.unsafeCast' call
  var tmp$ret$1 = array.slice();
  return ArrayList.new_kotlin_collections_ArrayList_qfbsh5_k$(tmp$ret$1);
}
function createMutableMapFrom(map) {
  // Inline function 'kotlin.apply' call
  var this_0 = LinkedHashMap.new_kotlin_collections_LinkedHashMap_ga0any_k$();
  forEach(createMutableMapFrom$lambda(this_0), map);
  return this_0;
}
function createJsReadonlyArrayViewFrom$lambda($list) {
  return () => $list.get_size_woubt6_k$();
}
function createJsReadonlyArrayViewFrom$lambda_0($list) {
  return (i) => $list.get_c1px32_k$(i);
}
function UNSUPPORTED_OPERATION$ref() {
  var l = () => {
    UNSUPPORTED_OPERATION();
    return Unit_instance;
  };
  l.callableName = 'UNSUPPORTED_OPERATION';
  return l;
}
function UNSUPPORTED_OPERATION$ref_0() {
  var l = () => {
    UNSUPPORTED_OPERATION();
    return Unit_instance;
  };
  l.callableName = 'UNSUPPORTED_OPERATION';
  return l;
}
function UNSUPPORTED_OPERATION$ref_1() {
  var l = () => {
    UNSUPPORTED_OPERATION();
    return Unit_instance;
  };
  l.callableName = 'UNSUPPORTED_OPERATION';
  return l;
}
function createJsReadonlyMapViewFrom$lambda($map) {
  return () => $map.get_size_woubt6_k$();
}
function createJsReadonlyMapViewFrom$lambda_0($map) {
  return (k) => $map.get_wei43m_k$(k);
}
function createJsReadonlyMapViewFrom$lambda_1($map) {
  return (k) => $map.containsKey_aw81wo_k$(k);
}
function UNSUPPORTED_OPERATION$ref_2() {
  var l = () => {
    UNSUPPORTED_OPERATION();
    return Unit_instance;
  };
  l.callableName = 'UNSUPPORTED_OPERATION';
  return l;
}
function UNSUPPORTED_OPERATION$ref_3() {
  var l = () => {
    UNSUPPORTED_OPERATION();
    return Unit_instance;
  };
  l.callableName = 'UNSUPPORTED_OPERATION';
  return l;
}
function UNSUPPORTED_OPERATION$ref_4() {
  var l = () => {
    UNSUPPORTED_OPERATION();
    return Unit_instance;
  };
  l.callableName = 'UNSUPPORTED_OPERATION';
  return l;
}
function createJsReadonlyMapViewFrom$lambda_2($map) {
  return () => createJsIteratorFrom($map.get_keys_wop4xp_k$().iterator_jk1svi_k$());
}
function createJsReadonlyMapViewFrom$lambda_3($map) {
  return () => createJsIteratorFrom($map.get_values_ksazhn_k$().iterator_jk1svi_k$());
}
function createJsReadonlyMapViewFrom$lambda$lambda(it) {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [it.get_key_18j28a_k$(), it.get_value_j01efc_k$()];
}
function createJsReadonlyMapViewFrom$lambda_4($map) {
  return () => {
    var tmp = $map.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    return createJsIteratorFrom(tmp, createJsReadonlyMapViewFrom$lambda$lambda);
  };
}
function createJsReadonlyMapViewFrom$lambda_5(callback, map, thisArg) {
  forEach(callback, map, thisArg);
  return Unit_instance;
}
function createJsMapViewFrom$lambda($map) {
  return () => $map.get_size_woubt6_k$();
}
function createJsMapViewFrom$lambda_0($map) {
  return (k) => $map.get_wei43m_k$(k);
}
function createJsMapViewFrom$lambda_1($map) {
  return (k) => $map.containsKey_aw81wo_k$(k);
}
function createJsMapViewFrom$lambda_2($map) {
  return (k, v) => {
    $map.put_4fpzoq_k$(k, v);
    return Unit_instance;
  };
}
function createJsMapViewFrom$lambda_3($map) {
  return (k) => {
    $map.remove_gppy8k_k$(k);
    return Unit_instance;
  };
}
function createJsMapViewFrom$lambda_4($map) {
  return () => {
    $map.clear_j9egeb_k$();
    return Unit_instance;
  };
}
function createJsMapViewFrom$lambda_5($map) {
  return () => createJsIteratorFrom($map.get_keys_wop4xp_k$().iterator_jk1svi_k$());
}
function createJsMapViewFrom$lambda_6($map) {
  return () => createJsIteratorFrom($map.get_values_ksazhn_k$().iterator_jk1svi_k$());
}
function createJsMapViewFrom$lambda$lambda(it) {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [it.get_key_18j28a_k$(), it.get_value_j01efc_k$()];
}
function createJsMapViewFrom$lambda_7($map) {
  return () => {
    var tmp = $map.get_entries_p20ztl_k$().iterator_jk1svi_k$();
    return createJsIteratorFrom(tmp, createJsMapViewFrom$lambda$lambda);
  };
}
function createJsMapViewFrom$lambda_8(callback, map, thisArg) {
  forEach(callback, map, thisArg);
  return Unit_instance;
}
function createJsArrayViewFrom$lambda($list) {
  return () => $list.get_size_woubt6_k$();
}
function createJsArrayViewFrom$lambda_0($list) {
  return (i) => $list.get_c1px32_k$(i);
}
function createJsArrayViewFrom$lambda_1($list) {
  return (i, v) => {
    $list.set_82063s_k$(i, v);
    return Unit_instance;
  };
}
function createJsArrayViewFrom$lambda_2($list) {
  return (size) => {
    $list.subList_xle3r2_k$($list.get_size_woubt6_k$() - size | 0, $list.get_size_woubt6_k$()).clear_j9egeb_k$();
    return Unit_instance;
  };
}
function UNSUPPORTED_OPERATION$ref_5() {
  var l = () => {
    UNSUPPORTED_OPERATION();
    return Unit_instance;
  };
  l.callableName = 'UNSUPPORTED_OPERATION';
  return l;
}
function createJsIteratorFrom$lambda(it) {
  return it;
}
function createJsIteratorFrom$lambda_0($iterator) {
  return () => $iterator.next_20eer_k$();
}
function createJsIteratorFrom$lambda_1($iterator) {
  return () => $iterator.hasNext_bitz1p_k$();
}
function createMutableMapFrom$lambda($$this$apply) {
  return (value, key, _unused_var__etf5q3) => {
    $$this$apply.put_4fpzoq_k$(key, value);
    return Unit_instance;
  };
}
function compareTo(a, b) {
  var tmp;
  switch (typeof a) {
    case 'number':
      var tmp_0;
      if (typeof b === 'number') {
        tmp_0 = doubleCompareTo(a, b);
      } else {
        if (!(b == null) ? typeof b === 'bigint' : false) {
          tmp_0 = doubleCompareTo(a, toNumber(b));
        } else {
          tmp_0 = primitiveCompareTo(a, b);
        }
      }

      tmp = tmp_0;
      break;
    case 'string':
    case 'boolean':
    case 'bigint':
      tmp = primitiveCompareTo(a, b);
      break;
    default:
      tmp = compareToDoNotIntrinsicify(a, b);
      break;
  }
  return tmp;
}
function doubleCompareTo(a, b) {
  var tmp;
  if (a < b) {
    tmp = -1;
  } else if (a > b) {
    tmp = 1;
  } else if (a === b) {
    var tmp_0;
    if (a !== 0) {
      tmp_0 = 0;
    } else {
      // Inline function 'kotlin.js.asDynamic' call
      var ia = 1 / a;
      var tmp_1;
      // Inline function 'kotlin.js.asDynamic' call
      if (ia === 1 / b) {
        tmp_1 = 0;
      } else {
        if (ia < 0) {
          tmp_1 = -1;
        } else {
          tmp_1 = 1;
        }
      }
      tmp_0 = tmp_1;
    }
    tmp = tmp_0;
  } else if (a !== a) {
    tmp = b !== b ? 0 : 1;
  } else {
    tmp = -1;
  }
  return tmp;
}
function primitiveCompareTo(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}
function compareToDoNotIntrinsicify(a, b) {
  return a.compareTo_hpufkf_k$(b);
}
function identityHashCode(obj) {
  return getObjectHashCode(obj);
}
function getObjectHashCode(obj) {
  // Inline function 'kotlin.js.jsIn' call
  if (!('kotlinHashCodeValue$' in obj)) {
    var hash = calculateRandomHash();
    var descriptor = new Object();
    descriptor.value = hash;
    descriptor.enumerable = false;
    Object.defineProperty(obj, 'kotlinHashCodeValue$', descriptor);
  }
  // Inline function 'kotlin.js.unsafeCast' call
  return obj['kotlinHashCodeValue$'];
}
function calculateRandomHash() {
  // Inline function 'kotlin.js.jsBitwiseOr' call
  return Math.random() * 4.294967296E9 | 0;
}
function toString_1(o) {
  var tmp;
  if (o == null) {
    tmp = 'null';
  } else if (isArrayish(o)) {
    tmp = '[...]';
  } else if (!(typeof o.toString === 'function')) {
    tmp = anyToString(o);
  } else {
    // Inline function 'kotlin.js.unsafeCast' call
    tmp = o.toString();
  }
  return tmp;
}
function objectCreate(proto) {
  proto = proto === VOID ? null : proto;
  return Object.create(proto);
}
function defineProp(obj, name, getter, setter, enumerable) {
  return Object.defineProperty(obj, name, {configurable: true, get: getter, set: setter, enumerable: enumerable});
}
function anyToString(o) {
  return Object.prototype.toString.call(o);
}
function hashCode(obj) {
  if (obj == null)
    return 0;
  var typeOf = typeof obj;
  var tmp;
  switch (typeOf) {
    case 'object':
      tmp = 'function' === typeof obj.hashCode ? obj.hashCode() : getObjectHashCode(obj);
      break;
    case 'function':
      tmp = getObjectHashCode(obj);
      break;
    case 'number':
      tmp = getNumberHashCode(obj);
      break;
    case 'boolean':
      // Inline function 'kotlin.js.unsafeCast' call

      tmp = getBooleanHashCode(obj);
      break;
    case 'string':
      tmp = getStringHashCode(String(obj));
      break;
    case 'bigint':
      // Inline function 'kotlin.js.unsafeCast' call

      tmp = getBigIntHashCode(obj);
      break;
    case 'symbol':
      tmp = getSymbolHashCode(obj);
      break;
    default:
      tmp = function () {
        throw new Error('Unexpected typeof `' + typeOf + '`');
      }();
      break;
  }
  return tmp;
}
function getBooleanHashCode(value) {
  return value ? 1231 : 1237;
}
function getStringHashCode(str) {
  var hash = 0;
  var length = str.length;
  var inductionVariable = 0;
  var last = length - 1 | 0;
  if (inductionVariable <= last)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      // Inline function 'kotlin.js.asDynamic' call
      var code = str.charCodeAt(i);
      hash = imul_0(hash, 31) + code | 0;
    }
     while (!(i === last));
  return hash;
}
function getBigIntHashCode(value) {
  var shiftNumber = BigInt(32);
  var mask = BigInt(4.294967295E9);
  var bigNumber = abs(value);
  var hashCode = 0;
  var tmp;
  // Inline function 'kotlin.js.internal.isNegative' call
  if (value < 0) {
    tmp = -1;
  } else {
    tmp = 1;
  }
  var signum = tmp;
  $l$loop: while (true) {
    // Inline function 'kotlin.js.internal.isZero' call
    if (!!(bigNumber == 0)) {
      break $l$loop;
    }
    // Inline function 'kotlin.js.internal.and' call
    // Inline function 'kotlin.js.jsBitwiseAnd' call
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    // Inline function 'kotlin.js.internal.toNumber' call
    var self_0 = bigNumber & mask;
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    var chunk = Number(self_0);
    hashCode = imul_0(31, hashCode) + chunk | 0;
    // Inline function 'kotlin.js.internal.shr' call
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    bigNumber = bigNumber >> shiftNumber;
  }
  return imul_0(hashCode, signum);
}
function getSymbolHashCode(value) {
  var hashCodeMap = symbolIsSharable(value) ? getSymbolMap() : getSymbolWeakMap();
  var cachedHashCode = hashCodeMap.get(value);
  if (cachedHashCode !== VOID)
    return cachedHashCode;
  var hash = calculateRandomHash();
  hashCodeMap.set(value, hash);
  return hash;
}
function symbolIsSharable(symbol) {
  return Symbol.keyFor(symbol) != VOID;
}
function getSymbolMap() {
  if (symbolMap === VOID) {
    symbolMap = new Map();
  }
  return symbolMap;
}
function getSymbolWeakMap() {
  if (symbolWeakMap === VOID) {
    symbolWeakMap = new WeakMap();
  }
  return symbolWeakMap;
}
var symbolMap;
var symbolWeakMap;
function equals(obj1, obj2) {
  if (obj1 == null) {
    return obj2 == null;
  }
  if (obj2 == null) {
    return false;
  }
  if (typeof obj1 === 'object' && typeof obj1.equals === 'function') {
    return obj1.equals(obj2);
  }
  if (obj1 !== obj1) {
    return obj2 !== obj2;
  }
  if (typeof obj1 === 'number' && typeof obj2 === 'number') {
    var tmp;
    if (obj1 === obj2) {
      var tmp_0;
      if (obj1 !== 0) {
        tmp_0 = true;
      } else {
        // Inline function 'kotlin.js.asDynamic' call
        var tmp_1 = 1 / obj1;
        // Inline function 'kotlin.js.asDynamic' call
        tmp_0 = tmp_1 === 1 / obj2;
      }
      tmp = tmp_0;
    } else {
      tmp = false;
    }
    return tmp;
  }
  return obj1 === obj2;
}
function unboxIntrinsic(x) {
  var message = 'Should be lowered';
  throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$(toString_1(message));
}
function captureStack(instance, constructorFunction) {
  if (Error.captureStackTrace != null) {
    Error.captureStackTrace(instance, constructorFunction);
  } else {
    // Inline function 'kotlin.js.asDynamic' call
    instance.stack = (new Error()).stack;
  }
}
function protoOf(constructor) {
  return constructor.prototype;
}
function createThis(ctor, box) {
  var self_0 = Object.create(ctor.prototype);
  boxApply(self_0, box);
  return self_0;
}
function boxApply(self_0, box) {
  if (box !== VOID) {
    Object.assign(self_0, box);
  }
}
function createExternalThis(ctor, superExternalCtor, parameters, box) {
  var tmp;
  if (box === VOID) {
    tmp = ctor;
  } else {
    var newCtor = class  extends ctor {}
    Object.assign(newCtor.prototype, box);
    newCtor.constructor = ctor;
    tmp = newCtor;
  }
  var selfCtor = tmp;
  return Reflect.construct(superExternalCtor, parameters, selfCtor);
}
function isUndefined(value) {
  return value === VOID;
}
function setPropertiesToThrowableInstance(this_, message, cause) {
  this_.name = Object.getPrototypeOf(this_).constructor.name;
  if (message == null) {
    var tmp;
    if (isUndefined(message)) {
      var tmp1_elvis_lhs = cause == null ? null : cause.toString();
      tmp = tmp1_elvis_lhs == null ? VOID : tmp1_elvis_lhs;
    } else {
      tmp = VOID;
    }
    this_.message = tmp;
  }
}
function ensureNotNull(v) {
  var tmp;
  if (v == null) {
    THROW_NPE();
  } else {
    tmp = v;
  }
  return tmp;
}
function THROW_NPE() {
  throw NullPointerException.new_kotlin_NullPointerException_q6jd54_k$();
}
function THROW_CCE() {
  throw ClassCastException.new_kotlin_ClassCastException_zhuhe1_k$();
}
function get_longArrayClass() {
  _init_properties_longAsBigInt_kt__j3nkxv();
  return longArrayClass;
}
var longArrayClass;
function shiftLeft(_this__u8e3s4, numBits) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  // Inline function 'kotlin.js.internal.longAsBigInt.wrappingArithmetic' call
  var tmp = BigInt;
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.internal.shl' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$7 = _this__u8e3s4 << fromInt(sanitizeBitShiftRHS(numBits));
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return tmp.asIntN(64, tmp$ret$7);
}
function sanitizeBitShiftRHS(numBits) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  return numBits & 63;
}
function shiftRight(_this__u8e3s4, numBits) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  // Inline function 'kotlin.js.internal.longAsBigInt.wrappingArithmetic' call
  var tmp = BigInt;
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.internal.shr' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$7 = _this__u8e3s4 >> fromInt(sanitizeBitShiftRHS(numBits));
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return tmp.asIntN(64, tmp$ret$7);
}
function fromInt(value) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return BigInt(value);
}
function truncating(_this__u8e3s4, bitSize) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  var tmp = BigInt;
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.internal.toNumber' call
  var self_0 = tmp.asIntN(bitSize, _this__u8e3s4);
  // Inline function 'kotlin.js.unsafeCast' call
  return Number(self_0);
}
function toNumber(_this__u8e3s4) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.internal.toNumber' call
  var self_0 = _this__u8e3s4;
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return Number(self_0);
}
function convertToInt(_this__u8e3s4) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return truncating(_this__u8e3s4, 32);
}
function longFromTwoInts(low, high) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  return shiftLeft(fromInt(high), 32) | fromInt(low) & 4294967295n;
}
function lowBits(_this__u8e3s4) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  return convertToInt(_this__u8e3s4);
}
function highBits(_this__u8e3s4) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  return convertToInt(shiftRight(_this__u8e3s4, 32));
}
function isLongArray(a) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  // Inline function 'kotlin.js.jsInstanceOf' call
  return a instanceof BigInt64Array;
}
function longArrayClass$lambda(it) {
  _init_properties_longAsBigInt_kt__j3nkxv();
  return !(it == null) ? isLongArray(it) : false;
}
var properties_initialized_longAsBigInt_kt_s7aby9;
function _init_properties_longAsBigInt_kt__j3nkxv() {
  if (!properties_initialized_longAsBigInt_kt_s7aby9) {
    properties_initialized_longAsBigInt_kt_s7aby9 = true;
    // Inline function 'kotlin.js.unsafeCast' call
    var tmp = BigInt64Array;
    longArrayClass = new PrimitiveKClassImpl(tmp, 'LongArray', longArrayClass$lambda);
  }
}
function createMetadata(kind, name, defaultConstructor, associatedObjectKey, associatedObjects, suspendArity) {
  var undef = VOID;
  var iid = kind === 'interface' ? generateInterfaceId() : VOID;
  return {kind: kind, simpleName: name, associatedObjectKey: associatedObjectKey, associatedObjects: associatedObjects, suspendArity: suspendArity, $kClass$: undef, defaultConstructor: defaultConstructor, iid: iid};
}
function generateInterfaceId() {
  if (globalInterfaceId === VOID) {
    globalInterfaceId = 0;
  }
  // Inline function 'kotlin.js.unsafeCast' call
  globalInterfaceId = globalInterfaceId + 1 | 0;
  // Inline function 'kotlin.js.unsafeCast' call
  return globalInterfaceId;
}
var globalInterfaceId;
function initMetadataForClass(ctor, name, defaultConstructor, parent, interfaces, suspendArity, associatedObjectKey, associatedObjects) {
  var kind = 'class';
  initMetadataFor(kind, ctor, name, defaultConstructor, parent, interfaces, suspendArity, associatedObjectKey, associatedObjects);
}
function initMetadataFor(kind, ctor, name, defaultConstructor, parent, interfaces, suspendArity, associatedObjectKey, associatedObjects) {
  if (!(parent == null)) {
    ctor.prototype = Object.create(parent.prototype);
    ctor.prototype.constructor = ctor;
  }
  var metadata = createMetadata(kind, name, defaultConstructor, associatedObjectKey, associatedObjects, suspendArity);
  ctor.$metadata$ = metadata;
  if (!(interfaces == null)) {
    var receiver = !equals(metadata.iid, VOID) ? ctor : ctor.prototype;
    receiver.$imask$ = implement(interfaces);
  }
}
function initMetadataForObject(ctor, name, defaultConstructor, parent, interfaces, suspendArity, associatedObjectKey, associatedObjects) {
  var kind = 'object';
  initMetadataFor(kind, ctor, name, defaultConstructor, parent, interfaces, suspendArity, associatedObjectKey, associatedObjects);
}
function initMetadataForInterface(ctor, name, defaultConstructor, parent, interfaces, suspendArity, associatedObjectKey, associatedObjects) {
  var kind = 'interface';
  initMetadataFor(kind, ctor, name, defaultConstructor, parent, interfaces, suspendArity, associatedObjectKey, associatedObjects);
}
function initMetadataForLambda(ctor, parent, interfaces, suspendArity) {
  initMetadataForClass(ctor, 'Lambda', VOID, parent, interfaces, suspendArity, VOID, VOID);
}
function initMetadataForCoroutine(ctor, parent, interfaces, suspendArity) {
  initMetadataForClass(ctor, 'Coroutine', VOID, parent, interfaces, suspendArity, VOID, VOID);
}
function initMetadataForFunctionReference(ctor, parent, interfaces, suspendArity) {
  initMetadataForClass(ctor, 'FunctionReference', VOID, parent, interfaces, suspendArity, VOID, VOID);
}
function initMetadataForCompanion(ctor, parent, interfaces, suspendArity) {
  initMetadataForObject(ctor, 'Companion', VOID, parent, interfaces, suspendArity, VOID, VOID);
}
function toByte(a) {
  // Inline function 'kotlin.js.unsafeCast' call
  return a << 24 >> 24;
}
function numberToInt(a) {
  var tmp;
  if (!(a == null) ? typeof a === 'bigint' : false) {
    tmp = convertToInt(a);
  } else {
    tmp = doubleToInt(a);
  }
  return tmp;
}
function doubleToInt(a) {
  var tmp;
  if (a > 2147483647) {
    tmp = 2147483647;
  } else if (a < -2147483648) {
    tmp = -2147483648;
  } else {
    // Inline function 'kotlin.js.jsBitwiseOr' call
    tmp = a | 0;
  }
  return tmp;
}
function toShort(a) {
  // Inline function 'kotlin.js.unsafeCast' call
  return a << 16 >> 16;
}
function numberToChar(a) {
  // Inline function 'kotlin.toUShort' call
  var this_0 = numberToInt(a);
  var tmp$ret$0 = _UShort___init__impl__jigrne(toShort(this_0));
  return _Char___init__impl__6a9atx_0(tmp$ret$0);
}
function isArrayish(o) {
  return isJsArray(o) || isView(o);
}
function isJsArray(obj) {
  // Inline function 'kotlin.js.unsafeCast' call
  return Array.isArray(obj);
}
function isInterface(obj, iface) {
  return isInterfaceImpl(obj, iface.$metadata$.iid);
}
function isInterfaceImpl(obj, iface) {
  // Inline function 'kotlin.js.unsafeCast' call
  var tmp0_elvis_lhs = obj.$imask$;
  var tmp;
  if (tmp0_elvis_lhs == null) {
    return false;
  } else {
    tmp = tmp0_elvis_lhs;
  }
  var mask = tmp;
  return isBitSet(mask, iface);
}
function isArray(obj) {
  var tmp;
  if (isJsArray(obj)) {
    // Inline function 'kotlin.js.asDynamic' call
    tmp = !obj.$type$;
  } else {
    tmp = false;
  }
  return tmp;
}
function isNumber(a) {
  var tmp;
  if (typeof a === 'number') {
    tmp = true;
  } else {
    tmp = !(a == null) ? typeof a === 'bigint' : false;
  }
  return tmp;
}
function isCharSequence(value) {
  return typeof value === 'string' || isInterface(value, CharSequence);
}
function isBooleanArray(a) {
  return isJsArray(a) && a.$type$ === 'BooleanArray';
}
function isByteArray(a) {
  // Inline function 'kotlin.js.jsInstanceOf' call
  return a instanceof Int8Array;
}
function isShortArray(a) {
  // Inline function 'kotlin.js.jsInstanceOf' call
  return a instanceof Int16Array;
}
function isCharArray(a) {
  var tmp;
  // Inline function 'kotlin.js.jsInstanceOf' call
  if (a instanceof Uint16Array) {
    tmp = a.$type$ === 'CharArray';
  } else {
    tmp = false;
  }
  return tmp;
}
function isIntArray(a) {
  // Inline function 'kotlin.js.jsInstanceOf' call
  return a instanceof Int32Array;
}
function isFloatArray(a) {
  // Inline function 'kotlin.js.jsInstanceOf' call
  return a instanceof Float32Array;
}
function isDoubleArray(a) {
  // Inline function 'kotlin.js.jsInstanceOf' call
  return a instanceof Float64Array;
}
function get_VOID() {
  _init_properties_void_kt__3zg9as();
  return VOID;
}
var VOID;
var properties_initialized_void_kt_e4ret2;
function _init_properties_void_kt__3zg9as() {
  if (!properties_initialized_void_kt_e4ret2) {
    properties_initialized_void_kt_e4ret2 = true;
    VOID = void 0;
  }
}
function fill(_this__u8e3s4, element, fromIndex, toIndex) {
  fromIndex = fromIndex === VOID ? 0 : fromIndex;
  toIndex = toIndex === VOID ? _this__u8e3s4.length : toIndex;
  Companion_instance_5.checkRangeIndexes_mmy49x_k$(fromIndex, toIndex, _this__u8e3s4.length);
  // Inline function 'kotlin.js.nativeFill' call
  // Inline function 'kotlin.js.asDynamic' call
  _this__u8e3s4.fill(element, fromIndex, toIndex);
}
function copyOf(_this__u8e3s4, newSize) {
  // Inline function 'kotlin.require' call
  if (!(newSize >= 0)) {
    var message = 'Invalid new array size: ' + newSize + '.';
    throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
  }
  return fillFrom(_this__u8e3s4, new Int32Array(newSize));
}
function copyOf_0(_this__u8e3s4, newSize) {
  // Inline function 'kotlin.require' call
  if (!(newSize >= 0)) {
    var message = 'Invalid new array size: ' + newSize + '.';
    throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
  }
  return arrayCopyResize(_this__u8e3s4, newSize, null);
}
function isWhitespaceImpl(_this__u8e3s4) {
  // Inline function 'kotlin.code' call
  var ch = Char__toInt_impl_vasixd(_this__u8e3s4);
  return (9 <= ch ? ch <= 13 : false) || (28 <= ch ? ch <= 32 : false) || ch === 160 || (ch > 4096 && (ch === 5760 || (8192 <= ch ? ch <= 8202 : false) || ch === 8232 || ch === 8233 || ch === 8239 || ch === 8287 || ch === 12288));
}
function takeHighestOneBit(_this__u8e3s4) {
  var tmp;
  if (_this__u8e3s4 === 0) {
    tmp = 0;
  } else {
    // Inline function 'kotlin.countLeadingZeroBits' call
    tmp = 1 << (31 - clz32(_this__u8e3s4) | 0);
  }
  return tmp;
}
var Unit_instance;
function Unit_getInstance() {
  return Unit_instance;
}
function ulongCompare(v1, v2) {
  return compareTo(v1 ^ -9223372036854775808n, v2 ^ -9223372036854775808n);
}
function collectionToArray(collection) {
  return collectionToArrayCommonImpl(collection);
}
function terminateCollectionToArray(collectionSize, array) {
  return array;
}
function arrayOfNulls(reference, size) {
  // Inline function 'kotlin.arrayOfNulls' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return Array(size);
}
function listOf(element) {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$2 = [element];
  return ArrayList.new_kotlin_collections_ArrayList_qfbsh5_k$(tmp$ret$2);
}
function mapCapacity(expectedSize) {
  return expectedSize;
}
function copyToArray(collection) {
  var tmp;
  // Inline function 'kotlin.js.asDynamic' call
  if (collection.toArray !== undefined) {
    // Inline function 'kotlin.js.asDynamic' call
    // Inline function 'kotlin.js.unsafeCast' call
    tmp = collection.toArray();
  } else {
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    tmp = collectionToArray(collection);
  }
  return tmp;
}
function checkIndexOverflow(index) {
  if (index < 0) {
    throwIndexOverflow();
  }
  return index;
}
function arrayCopy(source, destination, destinationOffset, startIndex, endIndex) {
  Companion_instance_5.checkRangeIndexes_mmy49x_k$(startIndex, endIndex, source.length);
  var rangeSize = endIndex - startIndex | 0;
  Companion_instance_5.checkRangeIndexes_mmy49x_k$(destinationOffset, destinationOffset + rangeSize | 0, destination.length);
  if (isView(destination) && isView(source)) {
    // Inline function 'kotlin.js.asDynamic' call
    var subrange = source.subarray(startIndex, endIndex);
    // Inline function 'kotlin.js.asDynamic' call
    destination.set(subrange, destinationOffset);
  } else {
    if (!(source === destination) || destinationOffset <= startIndex) {
      var inductionVariable = 0;
      if (inductionVariable < rangeSize)
        do {
          var index = inductionVariable;
          inductionVariable = inductionVariable + 1 | 0;
          destination[destinationOffset + index | 0] = source[startIndex + index | 0];
        }
         while (inductionVariable < rangeSize);
    } else {
      var inductionVariable_0 = rangeSize - 1 | 0;
      if (0 <= inductionVariable_0)
        do {
          var index_0 = inductionVariable_0;
          inductionVariable_0 = inductionVariable_0 + -1 | 0;
          destination[destinationOffset + index_0 | 0] = source[startIndex + index_0 | 0];
        }
         while (0 <= inductionVariable_0);
    }
  }
}
function AbstractMutableList$removeAll$lambda($elements) {
  return (it) => $elements.contains_aljjnj_k$(it);
}
function AbstractMutableList$retainAll$lambda($elements) {
  return (it) => !$elements.contains_aljjnj_k$(it);
}
function arrayOfUninitializedElements(capacity) {
  // Inline function 'kotlin.require' call
  if (!(capacity >= 0)) {
    var message = 'capacity must be non-negative.';
    throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
  }
  // Inline function 'kotlin.arrayOfNulls' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return Array(capacity);
}
function resetRange(_this__u8e3s4, fromIndex, toIndex) {
  // Inline function 'kotlin.js.nativeFill' call
  // Inline function 'kotlin.js.asDynamic' call
  _this__u8e3s4.fill(null, fromIndex, toIndex);
}
function copyOfUninitializedElements(_this__u8e3s4, newSize) {
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return copyOf_0(_this__u8e3s4, newSize);
}
function resetAt(_this__u8e3s4, index) {
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  _this__u8e3s4[index] = null;
}
var Companion_instance_3;
function Companion_getInstance_3() {
  if (Companion_instance_3 === VOID)
    new Companion_3();
  return Companion_instance_3;
}
function increaseLength($this, amount) {
  var previous = $this.get_size_woubt6_k$();
  // Inline function 'kotlin.js.asDynamic' call
  $this.array_1.length = $this.get_size_woubt6_k$() + amount | 0;
  return previous;
}
function rangeCheck($this, index) {
  // Inline function 'kotlin.apply' call
  Companion_instance_5.checkElementIndex_s0yg86_k$(index, $this.get_size_woubt6_k$());
  return index;
}
function insertionRangeCheck($this, index) {
  // Inline function 'kotlin.apply' call
  Companion_instance_5.checkPositionIndex_w4k0on_k$(index, $this.get_size_woubt6_k$());
  return index;
}
function init_kotlin_collections_HashMap(_this__u8e3s4) {
  _this__u8e3s4.entriesView_1 = null;
}
function init_kotlin_collections_HashSet(_this__u8e3s4) {
}
function computeHashSize($this, capacity) {
  return takeHighestOneBit(imul_0(coerceAtLeast(capacity, 1), 3));
}
function computeShift($this, hashSize) {
  // Inline function 'kotlin.countLeadingZeroBits' call
  return clz32(hashSize) + 1 | 0;
}
function checkForComodification($this) {
  if (!($this.map_1.modCount_1 === $this.expectedModCount_1))
    throw ConcurrentModificationException.new_kotlin_ConcurrentModificationException_snpq2y_k$('The backing map has been modified after this entry was obtained.');
}
function _get_capacity__a9k9f3($this) {
  return $this.keysArray_1.length;
}
function _get_hashSize__tftcho($this) {
  return $this.hashArray_1.length;
}
function registerModification($this) {
  $this.modCount_1 = $this.modCount_1 + 1 | 0;
}
function ensureExtraCapacity($this, n) {
  if (shouldCompact($this, n)) {
    compact($this, true);
  } else {
    ensureCapacity($this, $this.length_1 + n | 0);
  }
}
function shouldCompact($this, extraCapacity) {
  var spareCapacity = _get_capacity__a9k9f3($this) - $this.length_1 | 0;
  var gaps = $this.length_1 - $this.get_size_woubt6_k$() | 0;
  return spareCapacity < extraCapacity && (gaps + spareCapacity | 0) >= extraCapacity && gaps >= (_get_capacity__a9k9f3($this) / 4 | 0);
}
function ensureCapacity($this, minCapacity) {
  if (minCapacity < 0)
    throw RuntimeException.new_kotlin_RuntimeException_xu1s8h_k$('too many elements');
  if (minCapacity > _get_capacity__a9k9f3($this)) {
    var newSize = Companion_instance_5.newCapacity_k5ozfy_k$(_get_capacity__a9k9f3($this), minCapacity);
    $this.keysArray_1 = copyOfUninitializedElements($this.keysArray_1, newSize);
    var tmp = $this;
    var tmp0_safe_receiver = $this.valuesArray_1;
    tmp.valuesArray_1 = tmp0_safe_receiver == null ? null : copyOfUninitializedElements(tmp0_safe_receiver, newSize);
    $this.presenceArray_1 = copyOf($this.presenceArray_1, newSize);
    var newHashSize = computeHashSize(Companion_instance_4, newSize);
    if (newHashSize > _get_hashSize__tftcho($this)) {
      rehash($this, newHashSize);
    }
  }
}
function allocateValuesArray($this) {
  var curValuesArray = $this.valuesArray_1;
  if (!(curValuesArray == null))
    return curValuesArray;
  var newValuesArray = arrayOfUninitializedElements(_get_capacity__a9k9f3($this));
  $this.valuesArray_1 = newValuesArray;
  return newValuesArray;
}
function hash($this, key) {
  return key == null ? 0 : imul_0(hashCode(key), -1640531527) >>> $this.hashShift_1 | 0;
}
function compact($this, updateHashArray) {
  var i = 0;
  var j = 0;
  var valuesArray = $this.valuesArray_1;
  while (i < $this.length_1) {
    var hash = $this.presenceArray_1[i];
    if (hash >= 0) {
      $this.keysArray_1[j] = $this.keysArray_1[i];
      if (!(valuesArray == null)) {
        valuesArray[j] = valuesArray[i];
      }
      if (updateHashArray) {
        $this.presenceArray_1[j] = hash;
        $this.hashArray_1[hash] = j + 1 | 0;
      }
      j = j + 1 | 0;
    }
    i = i + 1 | 0;
  }
  resetRange($this.keysArray_1, j, $this.length_1);
  if (valuesArray == null)
    null;
  else {
    resetRange(valuesArray, j, $this.length_1);
  }
  $this.length_1 = j;
}
function rehash($this, newHashSize) {
  registerModification($this);
  if ($this.length_1 > $this._size_1) {
    compact($this, false);
  }
  $this.hashArray_1 = new Int32Array(newHashSize);
  $this.hashShift_1 = computeShift(Companion_instance_4, newHashSize);
  var i = 0;
  while (i < $this.length_1) {
    var _unary__edvuaz = i;
    i = _unary__edvuaz + 1 | 0;
    if (!putRehash($this, _unary__edvuaz)) {
      throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$('This cannot happen with fixed magic multiplier and grow-only hash array. Have object hashCodes changed?');
    }
  }
}
function putRehash($this, i) {
  var hash_0 = hash($this, $this.keysArray_1[i]);
  var probesLeft = $this.maxProbeDistance_1;
  while (true) {
    var index = $this.hashArray_1[hash_0];
    if (index === 0) {
      $this.hashArray_1[hash_0] = i + 1 | 0;
      $this.presenceArray_1[i] = hash_0;
      return true;
    }
    probesLeft = probesLeft - 1 | 0;
    if (probesLeft < 0)
      return false;
    var _unary__edvuaz = hash_0;
    hash_0 = _unary__edvuaz - 1 | 0;
    if (_unary__edvuaz === 0)
      hash_0 = _get_hashSize__tftcho($this) - 1 | 0;
  }
}
function findKey($this, key) {
  var hash_0 = hash($this, key);
  var probesLeft = $this.maxProbeDistance_1;
  while (true) {
    var index = $this.hashArray_1[hash_0];
    if (index === 0)
      return -1;
    if (index > 0 && equals($this.keysArray_1[index - 1 | 0], key))
      return index - 1 | 0;
    probesLeft = probesLeft - 1 | 0;
    if (probesLeft < 0)
      return -1;
    var _unary__edvuaz = hash_0;
    hash_0 = _unary__edvuaz - 1 | 0;
    if (_unary__edvuaz === 0)
      hash_0 = _get_hashSize__tftcho($this) - 1 | 0;
  }
}
function findValue($this, value) {
  var i = $this.length_1;
  $l$loop: while (true) {
    i = i - 1 | 0;
    if (!(i >= 0)) {
      break $l$loop;
    }
    if ($this.presenceArray_1[i] >= 0 && equals(ensureNotNull($this.valuesArray_1)[i], value))
      return i;
  }
  return -1;
}
function addKey($this, key) {
  $this.checkIsMutable_h5js84_k$();
  retry: while (true) {
    var hash_0 = hash($this, key);
    var tentativeMaxProbeDistance = coerceAtMost(imul_0($this.maxProbeDistance_1, 2), _get_hashSize__tftcho($this) / 2 | 0);
    var probeDistance = 0;
    while (true) {
      var index = $this.hashArray_1[hash_0];
      if (index <= 0) {
        if ($this.length_1 >= _get_capacity__a9k9f3($this)) {
          ensureExtraCapacity($this, 1);
          continue retry;
        }
        var _unary__edvuaz = $this.length_1;
        $this.length_1 = _unary__edvuaz + 1 | 0;
        var putIndex = _unary__edvuaz;
        $this.keysArray_1[putIndex] = key;
        $this.presenceArray_1[putIndex] = hash_0;
        $this.hashArray_1[hash_0] = putIndex + 1 | 0;
        $this._size_1 = $this._size_1 + 1 | 0;
        registerModification($this);
        if (probeDistance > $this.maxProbeDistance_1)
          $this.maxProbeDistance_1 = probeDistance;
        return putIndex;
      }
      if (equals($this.keysArray_1[index - 1 | 0], key)) {
        return -index | 0;
      }
      probeDistance = probeDistance + 1 | 0;
      if (probeDistance > tentativeMaxProbeDistance) {
        rehash($this, imul_0(_get_hashSize__tftcho($this), 2));
        continue retry;
      }
      var _unary__edvuaz_0 = hash_0;
      hash_0 = _unary__edvuaz_0 - 1 | 0;
      if (_unary__edvuaz_0 === 0)
        hash_0 = _get_hashSize__tftcho($this) - 1 | 0;
    }
  }
}
function removeEntryAt($this, index) {
  resetAt($this.keysArray_1, index);
  var tmp0_safe_receiver = $this.valuesArray_1;
  if (tmp0_safe_receiver == null)
    null;
  else {
    resetAt(tmp0_safe_receiver, index);
  }
  removeHashAt($this, $this.presenceArray_1[index]);
  $this.presenceArray_1[index] = -1;
  $this._size_1 = $this._size_1 - 1 | 0;
  registerModification($this);
}
function removeHashAt($this, removedHash) {
  var hash_0 = removedHash;
  var hole = removedHash;
  var probeDistance = 0;
  var patchAttemptsLeft = coerceAtMost(imul_0($this.maxProbeDistance_1, 2), _get_hashSize__tftcho($this) / 2 | 0);
  while (true) {
    var _unary__edvuaz = hash_0;
    hash_0 = _unary__edvuaz - 1 | 0;
    if (_unary__edvuaz === 0)
      hash_0 = _get_hashSize__tftcho($this) - 1 | 0;
    probeDistance = probeDistance + 1 | 0;
    if (probeDistance > $this.maxProbeDistance_1) {
      $this.hashArray_1[hole] = 0;
      return Unit_instance;
    }
    var index = $this.hashArray_1[hash_0];
    if (index === 0) {
      $this.hashArray_1[hole] = 0;
      return Unit_instance;
    }
    if (index < 0) {
      $this.hashArray_1[hole] = -1;
      hole = hash_0;
      probeDistance = 0;
    } else {
      var otherHash = hash($this, $this.keysArray_1[index - 1 | 0]);
      if (((otherHash - hash_0 | 0) & (_get_hashSize__tftcho($this) - 1 | 0)) >= probeDistance) {
        $this.hashArray_1[hole] = index;
        $this.presenceArray_1[index - 1 | 0] = hole;
        hole = hash_0;
        probeDistance = 0;
      }
    }
    patchAttemptsLeft = patchAttemptsLeft - 1 | 0;
    if (patchAttemptsLeft < 0) {
      $this.hashArray_1[hole] = -1;
      return Unit_instance;
    }
  }
}
function contentEquals($this, other) {
  return $this._size_1 === other.get_size_woubt6_k$() && $this.containsAllEntries_m9iqdx_k$(other.get_entries_p20ztl_k$());
}
function putEntry($this, entry) {
  var index = addKey($this, entry.get_key_18j28a_k$());
  var valuesArray = allocateValuesArray($this);
  if (index >= 0) {
    valuesArray[index] = entry.get_value_j01efc_k$();
    return true;
  }
  var oldValue = valuesArray[(-index | 0) - 1 | 0];
  if (!equals(entry.get_value_j01efc_k$(), oldValue)) {
    valuesArray[(-index | 0) - 1 | 0] = entry.get_value_j01efc_k$();
    return true;
  }
  return false;
}
function putAllEntries($this, from) {
  if (from.isEmpty_y1axqb_k$())
    return false;
  ensureExtraCapacity($this, from.get_size_woubt6_k$());
  var it = from.iterator_jk1svi_k$();
  var updated = false;
  while (it.hasNext_bitz1p_k$()) {
    if (putEntry($this, it.next_20eer_k$()))
      updated = true;
  }
  return updated;
}
var Companion_instance_4;
function Companion_getInstance_4() {
  return Companion_instance_4;
}
function init_kotlin_collections_LinkedHashMap(_this__u8e3s4) {
}
function init_kotlin_collections_LinkedHashSet(_this__u8e3s4) {
}
var CompletedContinuation_instance;
function CompletedContinuation_getInstance() {
  return CompletedContinuation_instance;
}
function get_dummyGenerator() {
  _init_properties_GeneratorCoroutineImpl_kt__4u0pi3();
  return dummyGenerator;
}
var dummyGenerator;
function get_GeneratorFunction() {
  _init_properties_GeneratorCoroutineImpl_kt__4u0pi3();
  return GeneratorFunction;
}
var GeneratorFunction;
function isGeneratorSuspendStep(value) {
  _init_properties_GeneratorCoroutineImpl_kt__4u0pi3();
  return value != null && value.constructor === get_GeneratorFunction();
}
var properties_initialized_GeneratorCoroutineImpl_kt_yzcfjb;
function _init_properties_GeneratorCoroutineImpl_kt__4u0pi3() {
  if (!properties_initialized_GeneratorCoroutineImpl_kt_yzcfjb) {
    properties_initialized_GeneratorCoroutineImpl_kt_yzcfjb = true;
    dummyGenerator = function *(COROUTINE_SUSPENDED, generatorRef) {
      var resultOrSuspended = generatorRef();
      if (resultOrSuspended === COROUTINE_SUSPENDED)
        resultOrSuspended = yield resultOrSuspended;
      return resultOrSuspended;
    };
    GeneratorFunction = get_dummyGenerator().constructor.prototype;
  }
}
function invokeSuspendSuperType(_this__u8e3s4, completion) {
  throw NotImplementedError.new_kotlin_NotImplementedError_8rzvsv_k$('It is intrinsic method');
}
function createCoroutineUninterceptedGeneratorVersion(_this__u8e3s4, completion) {
  // Inline function 'kotlin.coroutines.intrinsics.createCoroutineFromGeneratorFunction' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var continuation = new GeneratorCoroutineImpl(completion);
  var tmp = get_dummyGenerator();
  var tmp_0 = get_COROUTINE_SUSPENDED();
  continuation.addNewIterator_cdx7u0_k$(tmp(tmp_0, createCoroutineUninterceptedGeneratorVersion$lambda(continuation, _this__u8e3s4)));
  return continuation;
}
function await_0(promise, $completion) {
  var safe = SafeContinuation.new_kotlin_coroutines_SafeContinuation_3yuezq_k$(intercepted($completion));
  var tmp = await$lambda(safe);
  promise.then(tmp, await$lambda_0(safe));
  return safe.getOrThrow_23gqzp_k$();
}
function intercepted(_this__u8e3s4) {
  var tmp0_safe_receiver = _this__u8e3s4 instanceof InterceptedCoroutine ? _this__u8e3s4 : null;
  var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.intercepted_vh228x_k$();
  return tmp1_elvis_lhs == null ? _this__u8e3s4 : tmp1_elvis_lhs;
}
function promisify(fn) {
  return new Promise(promisify$lambda(fn));
}
function suspendOrReturn(generator, continuation) {
  var tmp;
  // Inline function 'kotlin.js.asDynamic' call
  if (continuation.constructor === GeneratorCoroutineImpl) {
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    tmp = continuation;
  } else {
    tmp = new GeneratorCoroutineImpl(continuation);
  }
  var generatorCoroutineImpl = tmp;
  var value = generator(generatorCoroutineImpl);
  if (!isGeneratorSuspendStep(value))
    return value;
  // Inline function 'kotlin.js.unsafeCast' call
  var iterator = value;
  generatorCoroutineImpl.addNewIterator_cdx7u0_k$(iterator);
  try {
    var iteratorStep = iterator.next();
    if (iteratorStep.done) {
      generatorCoroutineImpl.dropLastIterator_mimyvx_k$();
    }
    return iteratorStep.value;
  } catch ($p) {
    if ($p instanceof Error) {
      var e = $p;
      generatorCoroutineImpl.dropLastIterator_mimyvx_k$();
      throw e;
    } else {
      throw $p;
    }
  }
}
function createCoroutineUninterceptedGeneratorVersion$lambda($continuation, $this_createCoroutineUninterceptedGeneratorVersion) {
  return () => {
    var it = $continuation;
    // Inline function 'kotlin.js.asDynamic' call
    var a = $this_createCoroutineUninterceptedGeneratorVersion;
    return typeof a === 'function' ? a(it) : $this_createCoroutineUninterceptedGeneratorVersion.invoke_vgi6qb_k$(it);
  };
}
function await$lambda($continuation) {
  return (result) => {
    // Inline function 'kotlin.coroutines.resume' call
    var this_0 = $continuation;
    // Inline function 'kotlin.Companion.success' call
    var tmp$ret$0 = _Result___init__impl__xyqfz8(result);
    this_0.resumeWith_dtxwbr_k$(tmp$ret$0);
    return Unit_instance;
  };
}
function await$lambda_0($continuation) {
  return (error) => {
    // Inline function 'kotlin.coroutines.resumeWithException' call
    var this_0 = $continuation;
    // Inline function 'kotlin.Companion.failure' call
    var tmp$ret$0 = _Result___init__impl__xyqfz8(createFailure(error));
    this_0.resumeWith_dtxwbr_k$(tmp$ret$0);
    return Unit_instance;
  };
}
function promisify$lambda($fn) {
  return (resolve, reject) => {
    // Inline function 'kotlin.coroutines.Continuation' call
    var completion = new promisify$2$$inlined$Continuation$1(EmptyCoroutineContext_instance, resolve, reject);
    startCoroutine($fn, completion);
    return Unit_instance;
  };
}
function init_kotlin_UnsupportedOperationException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_IllegalStateException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_IllegalArgumentException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_RuntimeException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_2);
}
function init_kotlin_Exception(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_1);
}
function init_kotlin_NoSuchElementException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_IndexOutOfBoundsException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_ConcurrentModificationException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_Error(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_1);
}
function init_kotlin_ArithmeticException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_NullPointerException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_UninitializedPropertyAccessException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function init_kotlin_ClassCastException(_this__u8e3s4) {
  captureStack(_this__u8e3s4, _this__u8e3s4.$throwableCtor_3);
}
function fillFrom(src, dst) {
  var srcLen = src.length;
  var dstLen = dst.length;
  var index = 0;
  // Inline function 'kotlin.js.unsafeCast' call
  var arr = dst;
  while (index < srcLen && index < dstLen) {
    var tmp = index;
    var _unary__edvuaz = index;
    index = _unary__edvuaz + 1 | 0;
    arr[tmp] = src[_unary__edvuaz];
  }
  return dst;
}
function arrayCopyResize(source, newSize, defaultValue) {
  // Inline function 'kotlin.js.unsafeCast' call
  var result = source.slice(0, newSize);
  // Inline function 'kotlin.copyArrayType' call
  if (source.$type$ !== undefined) {
    result.$type$ = source.$type$;
  }
  var index = source.length;
  if (newSize > index) {
    // Inline function 'kotlin.js.asDynamic' call
    result.length = newSize;
    while (index < newSize) {
      var _unary__edvuaz = index;
      index = _unary__edvuaz + 1 | 0;
      result[_unary__edvuaz] = defaultValue;
    }
  }
  return result;
}
var NothingKClassImpl_instance;
function NothingKClassImpl_getInstance() {
  if (NothingKClassImpl_instance === VOID)
    new NothingKClassImpl();
  return NothingKClassImpl_instance;
}
function get_functionClasses() {
  _init_properties_primitives_kt__3fums4();
  return functionClasses;
}
var functionClasses;
function PrimitiveClasses$anyClass$lambda(it) {
  return !(it == null);
}
function PrimitiveClasses$numberClass$lambda(it) {
  return isNumber(it);
}
function PrimitiveClasses$booleanClass$lambda(it) {
  return !(it == null) ? typeof it === 'boolean' : false;
}
function PrimitiveClasses$byteClass$lambda(it) {
  return !(it == null) ? typeof it === 'number' : false;
}
function PrimitiveClasses$shortClass$lambda(it) {
  return !(it == null) ? typeof it === 'number' : false;
}
function PrimitiveClasses$intClass$lambda(it) {
  return !(it == null) ? typeof it === 'number' : false;
}
function PrimitiveClasses$longClass$lambda(it) {
  return !(it == null) ? typeof it === 'bigint' : false;
}
function PrimitiveClasses$floatClass$lambda(it) {
  return !(it == null) ? typeof it === 'number' : false;
}
function PrimitiveClasses$doubleClass$lambda(it) {
  return !(it == null) ? typeof it === 'number' : false;
}
function PrimitiveClasses$arrayClass$lambda(it) {
  return !(it == null) ? isArray(it) : false;
}
function PrimitiveClasses$stringClass$lambda(it) {
  return !(it == null) ? typeof it === 'string' : false;
}
function PrimitiveClasses$throwableClass$lambda(it) {
  return it instanceof Error;
}
function PrimitiveClasses$booleanArrayClass$lambda(it) {
  return !(it == null) ? isBooleanArray(it) : false;
}
function PrimitiveClasses$charArrayClass$lambda(it) {
  return !(it == null) ? isCharArray(it) : false;
}
function PrimitiveClasses$byteArrayClass$lambda(it) {
  return !(it == null) ? isByteArray(it) : false;
}
function PrimitiveClasses$shortArrayClass$lambda(it) {
  return !(it == null) ? isShortArray(it) : false;
}
function PrimitiveClasses$intArrayClass$lambda(it) {
  return !(it == null) ? isIntArray(it) : false;
}
function PrimitiveClasses$floatArrayClass$lambda(it) {
  return !(it == null) ? isFloatArray(it) : false;
}
function PrimitiveClasses$doubleArrayClass$lambda(it) {
  return !(it == null) ? isDoubleArray(it) : false;
}
function PrimitiveClasses$functionClass$lambda($arity) {
  return (it) => {
    var tmp;
    if (typeof it === 'function') {
      // Inline function 'kotlin.js.asDynamic' call
      tmp = it.length === $arity;
    } else {
      tmp = false;
    }
    return tmp;
  };
}
var PrimitiveClasses_instance;
function PrimitiveClasses_getInstance() {
  if (PrimitiveClasses_instance === VOID)
    new PrimitiveClasses();
  return PrimitiveClasses_instance;
}
var properties_initialized_primitives_kt_jle18u;
function _init_properties_primitives_kt__3fums4() {
  if (!properties_initialized_primitives_kt_jle18u) {
    properties_initialized_primitives_kt_jle18u = true;
    // Inline function 'kotlin.arrayOfNulls' call
    functionClasses = Array(0);
  }
}
function getKClass(jClass) {
  if (jClass === String) {
    // Inline function 'kotlin.js.unsafeCast' call
    // Inline function 'kotlin.js.asDynamic' call
    return PrimitiveClasses_getInstance().stringClass;
  }
  // Inline function 'kotlin.js.asDynamic' call
  var metadata = jClass.$metadata$;
  var tmp;
  if (metadata != null) {
    var tmp_0;
    if (metadata.$kClass$ == null) {
      var kClass = new SimpleKClassImpl(jClass);
      metadata.$kClass$ = kClass;
      tmp_0 = kClass;
    } else {
      tmp_0 = metadata.$kClass$;
    }
    tmp = tmp_0;
  } else {
    tmp = new SimpleKClassImpl(jClass);
  }
  return tmp;
}
function getKClassFromExpression(e) {
  var tmp;
  switch (typeof e) {
    case 'string':
      tmp = PrimitiveClasses_getInstance().stringClass;
      break;
    case 'number':
      var tmp_0;
      // Inline function 'kotlin.js.jsBitwiseOr' call

      // Inline function 'kotlin.js.asDynamic' call

      if ((e | 0) === e) {
        tmp_0 = PrimitiveClasses_getInstance().intClass;
      } else {
        tmp_0 = PrimitiveClasses_getInstance().doubleClass;
      }

      tmp = tmp_0;
      break;
    case 'boolean':
      tmp = PrimitiveClasses_getInstance().booleanClass;
      break;
    case 'function':
      var tmp_1 = PrimitiveClasses_getInstance();
      // Inline function 'kotlin.js.asDynamic' call

      tmp = tmp_1.functionClass(e.length);
      break;
    default:
      var tmp_2;
      if (isBooleanArray(e)) {
        tmp_2 = PrimitiveClasses_getInstance().booleanArrayClass;
      } else {
        if (isCharArray(e)) {
          tmp_2 = PrimitiveClasses_getInstance().charArrayClass;
        } else {
          if (isByteArray(e)) {
            tmp_2 = PrimitiveClasses_getInstance().byteArrayClass;
          } else {
            if (isShortArray(e)) {
              tmp_2 = PrimitiveClasses_getInstance().shortArrayClass;
            } else {
              if (isIntArray(e)) {
                tmp_2 = PrimitiveClasses_getInstance().intArrayClass;
              } else {
                if (isLongArray(e)) {
                  tmp_2 = get_longArrayClass();
                } else {
                  if (isFloatArray(e)) {
                    tmp_2 = PrimitiveClasses_getInstance().floatArrayClass;
                  } else {
                    if (isDoubleArray(e)) {
                      tmp_2 = PrimitiveClasses_getInstance().doubleArrayClass;
                    } else {
                      if (isInterface(e, KClass)) {
                        tmp_2 = getKClass(KClass);
                      } else {
                        if (isArray(e)) {
                          tmp_2 = PrimitiveClasses_getInstance().arrayClass;
                        } else {
                          var constructor = Object.getPrototypeOf(e).constructor;
                          var tmp_3;
                          if (constructor === Object) {
                            tmp_3 = PrimitiveClasses_getInstance().anyClass;
                          } else if (constructor === Error) {
                            tmp_3 = PrimitiveClasses_getInstance().throwableClass;
                          } else {
                            var jsClass = constructor;
                            tmp_3 = getKClass(jsClass);
                          }
                          tmp_2 = tmp_3;
                        }
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }

      tmp = tmp_2;
      break;
  }
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return tmp;
}
function uppercaseChar(_this__u8e3s4) {
  // Inline function 'kotlin.text.uppercase' call
  // Inline function 'kotlin.js.asDynamic' call
  // Inline function 'kotlin.js.unsafeCast' call
  var uppercase = toString(_this__u8e3s4).toUpperCase();
  return uppercase.length > 1 ? _this__u8e3s4 : charCodeAt(uppercase, 0);
}
function isWhitespace(_this__u8e3s4) {
  return isWhitespaceImpl(_this__u8e3s4);
}
var STRING_CASE_INSENSITIVE_ORDER;
function substring(_this__u8e3s4, startIndex, endIndex) {
  _init_properties_stringJs_kt__bg7zye();
  // Inline function 'kotlin.js.asDynamic' call
  return _this__u8e3s4.substring(startIndex, endIndex);
}
function compareTo_0(_this__u8e3s4, other, ignoreCase) {
  ignoreCase = ignoreCase === VOID ? false : ignoreCase;
  _init_properties_stringJs_kt__bg7zye();
  if (ignoreCase) {
    var n1 = _this__u8e3s4.length;
    var n2 = other.length;
    // Inline function 'kotlin.comparisons.minOf' call
    var min = Math.min(n1, n2);
    if (min === 0)
      return n1 - n2 | 0;
    var inductionVariable = 0;
    if (inductionVariable < min)
      do {
        var index = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        var thisChar = charCodeAt(_this__u8e3s4, index);
        var otherChar = charCodeAt(other, index);
        if (!(thisChar === otherChar)) {
          thisChar = uppercaseChar(thisChar);
          otherChar = uppercaseChar(otherChar);
          if (!(thisChar === otherChar)) {
            // Inline function 'kotlin.text.lowercaseChar' call
            // Inline function 'kotlin.text.lowercase' call
            var this_0 = thisChar;
            // Inline function 'kotlin.js.asDynamic' call
            // Inline function 'kotlin.js.unsafeCast' call
            var tmp$ret$3 = toString(this_0).toLowerCase();
            thisChar = charCodeAt(tmp$ret$3, 0);
            // Inline function 'kotlin.text.lowercaseChar' call
            // Inline function 'kotlin.text.lowercase' call
            var this_1 = otherChar;
            // Inline function 'kotlin.js.asDynamic' call
            // Inline function 'kotlin.js.unsafeCast' call
            var tmp$ret$7 = toString(this_1).toLowerCase();
            otherChar = charCodeAt(tmp$ret$7, 0);
            if (!(thisChar === otherChar)) {
              return Char__compareTo_impl_ypi4mb(thisChar, otherChar);
            }
          }
        }
      }
       while (inductionVariable < min);
    return n1 - n2 | 0;
  } else {
    return compareTo(_this__u8e3s4, other);
  }
}
function decodeToString(_this__u8e3s4) {
  _init_properties_stringJs_kt__bg7zye();
  return decodeUtf8(_this__u8e3s4, 0, _this__u8e3s4.length, false);
}
function STRING_CASE_INSENSITIVE_ORDER$lambda(a, b) {
  _init_properties_stringJs_kt__bg7zye();
  return compareTo_0(a, b, true);
}
var properties_initialized_stringJs_kt_nta8o4;
function _init_properties_stringJs_kt__bg7zye() {
  if (!properties_initialized_stringJs_kt_nta8o4) {
    properties_initialized_stringJs_kt_nta8o4 = true;
    var tmp = STRING_CASE_INSENSITIVE_ORDER$lambda;
    STRING_CASE_INSENSITIVE_ORDER = new sam$kotlin_Comparator$0(tmp);
  }
}
var REPLACEMENT_BYTE_SEQUENCE;
function decodeUtf8(bytes, startIndex, endIndex, throwOnMalformed) {
  _init_properties_utf8Encoding_kt__9thjs4();
  // Inline function 'kotlin.require' call
  // Inline function 'kotlin.require' call
  if (!(startIndex >= 0 && endIndex <= bytes.length && startIndex <= endIndex)) {
    var message = 'Failed requirement.';
    throw IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(toString_1(message));
  }
  var byteIndex = startIndex;
  var stringBuilder = StringBuilder.new_kotlin_text_StringBuilder_u46mrb_k$();
  while (byteIndex < endIndex) {
    var _unary__edvuaz = byteIndex;
    byteIndex = _unary__edvuaz + 1 | 0;
    var byte = bytes[_unary__edvuaz];
    if (byte >= 0)
      stringBuilder.append_t84oo1_k$(numberToChar(byte));
    else if (byte >> 5 === -2) {
      var code = codePointFrom2(bytes, byte, byteIndex, endIndex, throwOnMalformed);
      if (code <= 0) {
        stringBuilder.append_t84oo1_k$(_Char___init__impl__6a9atx(65533));
        byteIndex = byteIndex + (-code | 0) | 0;
      } else {
        stringBuilder.append_t84oo1_k$(numberToChar(code));
        byteIndex = byteIndex + 1 | 0;
      }
    } else if (byte >> 4 === -2) {
      var code_0 = codePointFrom3(bytes, byte, byteIndex, endIndex, throwOnMalformed);
      if (code_0 <= 0) {
        stringBuilder.append_t84oo1_k$(_Char___init__impl__6a9atx(65533));
        byteIndex = byteIndex + (-code_0 | 0) | 0;
      } else {
        stringBuilder.append_t84oo1_k$(numberToChar(code_0));
        byteIndex = byteIndex + 2 | 0;
      }
    } else if (byte >> 3 === -2) {
      var code_1 = codePointFrom4(bytes, byte, byteIndex, endIndex, throwOnMalformed);
      if (code_1 <= 0) {
        stringBuilder.append_t84oo1_k$(_Char___init__impl__6a9atx(65533));
        byteIndex = byteIndex + (-code_1 | 0) | 0;
      } else {
        var high = (code_1 - 65536 | 0) >> 10 | 55296;
        var low = code_1 & 1023 | 56320;
        stringBuilder.append_t84oo1_k$(numberToChar(high));
        stringBuilder.append_t84oo1_k$(numberToChar(low));
        byteIndex = byteIndex + 3 | 0;
      }
    } else {
      malformed(0, byteIndex, throwOnMalformed);
      stringBuilder.append_t84oo1_k$(_Char___init__impl__6a9atx(65533));
    }
  }
  return stringBuilder.toString();
}
function codePointFrom2(bytes, byte1, index, endIndex, throwOnMalformed) {
  _init_properties_utf8Encoding_kt__9thjs4();
  if ((byte1 & 30) === 0 || index >= endIndex) {
    return malformed(0, index, throwOnMalformed);
  }
  var byte2 = bytes[index];
  if (!((byte2 & 192) === 128)) {
    return malformed(0, index, throwOnMalformed);
  }
  return byte1 << 6 ^ byte2 ^ 3968;
}
function codePointFrom3(bytes, byte1, index, endIndex, throwOnMalformed) {
  _init_properties_utf8Encoding_kt__9thjs4();
  if (index >= endIndex) {
    return malformed(0, index, throwOnMalformed);
  }
  var byte2 = bytes[index];
  if ((byte1 & 15) === 0) {
    if (!((byte2 & 224) === 160)) {
      return malformed(0, index, throwOnMalformed);
    }
  } else if ((byte1 & 15) === 13) {
    if (!((byte2 & 224) === 128)) {
      return malformed(0, index, throwOnMalformed);
    }
  } else if (!((byte2 & 192) === 128)) {
    return malformed(0, index, throwOnMalformed);
  }
  if ((index + 1 | 0) === endIndex) {
    return malformed(1, index, throwOnMalformed);
  }
  var byte3 = bytes[index + 1 | 0];
  if (!((byte3 & 192) === 128)) {
    return malformed(1, index, throwOnMalformed);
  }
  return byte1 << 12 ^ byte2 << 6 ^ byte3 ^ -123008;
}
function codePointFrom4(bytes, byte1, index, endIndex, throwOnMalformed) {
  _init_properties_utf8Encoding_kt__9thjs4();
  if (index >= endIndex) {
    return malformed(0, index, throwOnMalformed);
  }
  var byte2 = bytes[index];
  if ((byte1 & 15) === 0) {
    if ((byte2 & 240) <= 128) {
      return malformed(0, index, throwOnMalformed);
    }
  } else if ((byte1 & 15) === 4) {
    if (!((byte2 & 240) === 128)) {
      return malformed(0, index, throwOnMalformed);
    }
  } else if ((byte1 & 15) > 4) {
    return malformed(0, index, throwOnMalformed);
  }
  if (!((byte2 & 192) === 128)) {
    return malformed(0, index, throwOnMalformed);
  }
  if ((index + 1 | 0) === endIndex) {
    return malformed(1, index, throwOnMalformed);
  }
  var byte3 = bytes[index + 1 | 0];
  if (!((byte3 & 192) === 128)) {
    return malformed(1, index, throwOnMalformed);
  }
  if ((index + 2 | 0) === endIndex) {
    return malformed(2, index, throwOnMalformed);
  }
  var byte4 = bytes[index + 2 | 0];
  if (!((byte4 & 192) === 128)) {
    return malformed(2, index, throwOnMalformed);
  }
  return byte1 << 18 ^ byte2 << 12 ^ byte3 << 6 ^ byte4 ^ 3678080;
}
function malformed(size, index, throwOnMalformed) {
  _init_properties_utf8Encoding_kt__9thjs4();
  if (throwOnMalformed)
    throw CharacterCodingException.new_kotlin_text_CharacterCodingException_wozcvs_k$('Malformed sequence starting at ' + (index - 1 | 0));
  return -size | 0;
}
var properties_initialized_utf8Encoding_kt_eee1vq;
function _init_properties_utf8Encoding_kt__9thjs4() {
  if (!properties_initialized_utf8Encoding_kt_eee1vq) {
    properties_initialized_utf8Encoding_kt_eee1vq = true;
    // Inline function 'kotlin.byteArrayOf' call
    REPLACEMENT_BYTE_SEQUENCE = new Int8Array([-17, -65, -67]);
  }
}
function formatBytesInto(_this__u8e3s4, dst, dstOffset, startIndex, endIndex) {
  var dstIndex = dstOffset;
  if (startIndex < 4) {
    dstIndex = formatBytesInto_0(highBits(_this__u8e3s4), dst, dstIndex, startIndex, coerceAtMost(endIndex, 4));
  }
  if (endIndex > 4) {
    formatBytesInto_0(lowBits(_this__u8e3s4), dst, dstIndex, coerceAtLeast(startIndex - 4 | 0, 0), endIndex - 4 | 0);
  }
}
function getLongAt(_this__u8e3s4, index) {
  var tmp0_high = getIntAt(_this__u8e3s4, index);
  var tmp1_low = getIntAt(_this__u8e3s4, index + 4 | 0);
  return longFromTwoInts(tmp1_low, tmp0_high);
}
function secureRandomBytes(destination) {
  crypto.getRandomValues(destination);
}
function formatBytesInto_0(_this__u8e3s4, dst, dstOffset, startIndex, endIndex) {
  var dstIndex = dstOffset;
  var inductionVariable = 3 - startIndex | 0;
  var last = 4 - endIndex | 0;
  if (last <= inductionVariable)
    do {
      var reversedIndex = inductionVariable;
      inductionVariable = inductionVariable + -1 | 0;
      var shift = reversedIndex << 3;
      var byte = _this__u8e3s4 >> shift & 255;
      var byteDigits = get_BYTE_TO_LOWER_CASE_HEX_DIGITS()[byte];
      var _unary__edvuaz = dstIndex;
      dstIndex = _unary__edvuaz + 1 | 0;
      dst[_unary__edvuaz] = toByte(byteDigits >> 8);
      var _unary__edvuaz_0 = dstIndex;
      dstIndex = _unary__edvuaz_0 + 1 | 0;
      dst[_unary__edvuaz_0] = toByte(byteDigits);
    }
     while (!(reversedIndex === last));
  return dstIndex;
}
function getIntAt(_this__u8e3s4, index) {
  return (_this__u8e3s4[index + 0 | 0] & 255) << 24 | (_this__u8e3s4[index + 1 | 0] & 255) << 16 | (_this__u8e3s4[index + 2 | 0] & 255) << 8 | _this__u8e3s4[index + 3 | 0] & 255;
}
function AbstractCollection$toString$lambda(this$0) {
  return (it) => it === this$0 ? '(this Collection)' : toString_0(it);
}
var Companion_instance_5;
function Companion_getInstance_5() {
  return Companion_instance_5;
}
function toString_2($this, entry) {
  return toString_3($this, entry.get_key_18j28a_k$()) + '=' + toString_3($this, entry.get_value_j01efc_k$());
}
function toString_3($this, o) {
  return o === $this ? '(this Map)' : toString_0(o);
}
function implFindEntry($this, key) {
  var tmp0 = $this.get_entries_p20ztl_k$();
  var tmp$ret$1;
  $l$block: {
    // Inline function 'kotlin.collections.firstOrNull' call
    var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      if (equals(element.get_key_18j28a_k$(), key)) {
        tmp$ret$1 = element;
        break $l$block;
      }
    }
    tmp$ret$1 = null;
  }
  return tmp$ret$1;
}
var Companion_instance_6;
function Companion_getInstance_6() {
  return Companion_instance_6;
}
function AbstractMap$toString$lambda(this$0) {
  return (it) => toString_2(this$0, it);
}
var Companion_instance_7;
function Companion_getInstance_7() {
  return Companion_instance_7;
}
function ensureCapacity_0($this, minCapacity) {
  if (minCapacity < 0)
    throw IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$('Deque is too big.');
  if (minCapacity <= $this.elementData_1.length)
    return Unit_instance;
  if ($this.elementData_1 === Companion_getInstance_8().emptyElementData_1) {
    var tmp = $this;
    // Inline function 'kotlin.arrayOfNulls' call
    var size = coerceAtLeast(minCapacity, 10);
    tmp.elementData_1 = Array(size);
    return Unit_instance;
  }
  var newCapacity = Companion_instance_5.newCapacity_k5ozfy_k$($this.elementData_1.length, minCapacity);
  copyElements($this, newCapacity);
}
function copyElements($this, newCapacity) {
  // Inline function 'kotlin.arrayOfNulls' call
  var newElements = Array(newCapacity);
  var tmp0 = $this.elementData_1;
  var tmp6 = $this.head_1;
  // Inline function 'kotlin.collections.copyInto' call
  var endIndex = $this.elementData_1.length;
  arrayCopy(tmp0, newElements, 0, tmp6, endIndex);
  var tmp0_0 = $this.elementData_1;
  var tmp4 = $this.elementData_1.length - $this.head_1 | 0;
  // Inline function 'kotlin.collections.copyInto' call
  var endIndex_0 = $this.head_1;
  arrayCopy(tmp0_0, newElements, tmp4, 0, endIndex_0);
  $this.head_1 = 0;
  $this.elementData_1 = newElements;
}
function positiveMod($this, index) {
  return index >= $this.elementData_1.length ? index - $this.elementData_1.length | 0 : index;
}
function negativeMod($this, index) {
  return index < 0 ? index + $this.elementData_1.length | 0 : index;
}
function incremented($this, index) {
  return index === get_lastIndex($this.elementData_1) ? 0 : index + 1 | 0;
}
function decremented($this, index) {
  return index === 0 ? get_lastIndex($this.elementData_1) : index - 1 | 0;
}
function copyCollectionElements($this, internalIndex, elements) {
  var iterator = elements.iterator_jk1svi_k$();
  var inductionVariable = internalIndex;
  var last = $this.elementData_1.length;
  if (inductionVariable < last)
    $l$loop: do {
      var index = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      if (!iterator.hasNext_bitz1p_k$())
        break $l$loop;
      $this.elementData_1[index] = iterator.next_20eer_k$();
    }
     while (inductionVariable < last);
  var inductionVariable_0 = 0;
  var last_0 = $this.head_1;
  if (inductionVariable_0 < last_0)
    $l$loop_0: do {
      var index_0 = inductionVariable_0;
      inductionVariable_0 = inductionVariable_0 + 1 | 0;
      if (!iterator.hasNext_bitz1p_k$())
        break $l$loop_0;
      $this.elementData_1[index_0] = iterator.next_20eer_k$();
    }
     while (inductionVariable_0 < last_0);
  $this.size_1 = $this.size_1 + elements.get_size_woubt6_k$() | 0;
}
function removeRangeShiftPreceding($this, fromIndex, toIndex) {
  // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
  var index = fromIndex - 1 | 0;
  var copyFromIndex = positiveMod($this, $this.head_1 + index | 0);
  // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
  var index_0 = toIndex - 1 | 0;
  var copyToIndex = positiveMod($this, $this.head_1 + index_0 | 0);
  var copyCount = fromIndex;
  while (copyCount > 0) {
    var tmp0 = copyCount;
    var tmp2 = copyFromIndex + 1 | 0;
    // Inline function 'kotlin.comparisons.minOf' call
    var c = copyToIndex + 1 | 0;
    var segmentLength = Math.min(tmp0, tmp2, c);
    var tmp0_0 = $this.elementData_1;
    var tmp2_0 = $this.elementData_1;
    var tmp4 = (copyToIndex - segmentLength | 0) + 1 | 0;
    var tmp6 = (copyFromIndex - segmentLength | 0) + 1 | 0;
    // Inline function 'kotlin.collections.copyInto' call
    var endIndex = copyFromIndex + 1 | 0;
    arrayCopy(tmp0_0, tmp2_0, tmp4, tmp6, endIndex);
    copyFromIndex = negativeMod($this, copyFromIndex - segmentLength | 0);
    copyToIndex = negativeMod($this, copyToIndex - segmentLength | 0);
    copyCount = copyCount - segmentLength | 0;
  }
}
function removeRangeShiftSucceeding($this, fromIndex, toIndex) {
  // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
  var copyFromIndex = positiveMod($this, $this.head_1 + toIndex | 0);
  // Inline function 'kotlin.collections.ArrayDeque.internalIndex' call
  var copyToIndex = positiveMod($this, $this.head_1 + fromIndex | 0);
  var copyCount = $this.size_1 - toIndex | 0;
  while (copyCount > 0) {
    var tmp0 = copyCount;
    var tmp2 = $this.elementData_1.length - copyFromIndex | 0;
    // Inline function 'kotlin.comparisons.minOf' call
    var c = $this.elementData_1.length - copyToIndex | 0;
    var segmentLength = Math.min(tmp0, tmp2, c);
    var tmp0_0 = $this.elementData_1;
    var tmp2_0 = $this.elementData_1;
    var tmp4 = copyToIndex;
    var tmp6 = copyFromIndex;
    // Inline function 'kotlin.collections.copyInto' call
    var endIndex = copyFromIndex + segmentLength | 0;
    arrayCopy(tmp0_0, tmp2_0, tmp4, tmp6, endIndex);
    copyFromIndex = positiveMod($this, copyFromIndex + segmentLength | 0);
    copyToIndex = positiveMod($this, copyToIndex + segmentLength | 0);
    copyCount = copyCount - segmentLength | 0;
  }
}
function nullifyNonEmpty($this, internalFromIndex, internalToIndex) {
  if (internalFromIndex < internalToIndex) {
    fill($this.elementData_1, null, internalFromIndex, internalToIndex);
  } else {
    fill($this.elementData_1, null, internalFromIndex, $this.elementData_1.length);
    fill($this.elementData_1, null, 0, internalToIndex);
  }
}
function registerModification_0($this) {
  $this.modCount_1 = $this.modCount_1 + 1 | 0;
}
var Companion_instance_8;
function Companion_getInstance_8() {
  if (Companion_instance_8 === VOID)
    new Companion_8();
  return Companion_instance_8;
}
function init_kotlin_collections_ArrayDeque(_this__u8e3s4) {
  Companion_getInstance_8();
  _this__u8e3s4.head_1 = 0;
  _this__u8e3s4.size_1 = 0;
}
function collectionToArrayCommonImpl(collection) {
  if (collection.isEmpty_y1axqb_k$()) {
    // Inline function 'kotlin.emptyArray' call
    return [];
  }
  // Inline function 'kotlin.arrayOfNulls' call
  var size = collection.get_size_woubt6_k$();
  var destination = Array(size);
  var iterator = collection.iterator_jk1svi_k$();
  var index = 0;
  while (iterator.hasNext_bitz1p_k$()) {
    var _unary__edvuaz = index;
    index = _unary__edvuaz + 1 | 0;
    destination[_unary__edvuaz] = iterator.next_20eer_k$();
  }
  return destination;
}
function emptyList() {
  return EmptyList_instance;
}
var EmptyList_instance;
function EmptyList_getInstance() {
  return EmptyList_instance;
}
function get_lastIndex_0(_this__u8e3s4) {
  return _this__u8e3s4.get_size_woubt6_k$() - 1 | 0;
}
var EmptyIterator_instance;
function EmptyIterator_getInstance() {
  return EmptyIterator_instance;
}
function optimizeReadOnlyList(_this__u8e3s4) {
  switch (_this__u8e3s4.get_size_woubt6_k$()) {
    case 0:
      return emptyList();
    case 1:
      return listOf(_this__u8e3s4.get_c1px32_k$(0));
    default:
      return _this__u8e3s4;
  }
}
function throwIndexOverflow() {
  throw ArithmeticException.new_kotlin_ArithmeticException_y2sjkx_k$('Index overflow has happened.');
}
function collectionSizeOrDefault(_this__u8e3s4, default_0) {
  var tmp;
  if (isInterface(_this__u8e3s4, Collection)) {
    tmp = _this__u8e3s4.get_size_woubt6_k$();
  } else {
    tmp = default_0;
  }
  return tmp;
}
function mutableMapOf(pairs) {
  // Inline function 'kotlin.apply' call
  var this_0 = LinkedHashMap.new_kotlin_collections_LinkedHashMap_31p40q_k$(mapCapacity(pairs.length));
  putAll(this_0, pairs);
  return this_0;
}
function emptyMap() {
  var tmp = EmptyMap_instance;
  return isInterface(tmp, KtMap) ? tmp : THROW_CCE();
}
function putAll(_this__u8e3s4, pairs) {
  var inductionVariable = 0;
  var last = pairs.length;
  while (inductionVariable < last) {
    var _destruct__k2r9zo = pairs[inductionVariable];
    inductionVariable = inductionVariable + 1 | 0;
    var key = _destruct__k2r9zo.component1_7eebsc_k$();
    var value = _destruct__k2r9zo.component2_7eebsb_k$();
    _this__u8e3s4.put_4fpzoq_k$(key, value);
  }
}
var EmptyMap_instance;
function EmptyMap_getInstance() {
  return EmptyMap_instance;
}
function convertToListIfNotCollection(_this__u8e3s4) {
  var tmp;
  if (isInterface(_this__u8e3s4, Collection)) {
    tmp = _this__u8e3s4;
  } else {
    tmp = toList(_this__u8e3s4);
  }
  return tmp;
}
function addAll(_this__u8e3s4, elements) {
  if (isInterface(elements, Collection))
    return _this__u8e3s4.addAll_h3ej1q_k$(elements);
  else {
    var result = false;
    var _iterator__ex2g4s = elements.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var item = _iterator__ex2g4s.next_20eer_k$();
      if (_this__u8e3s4.add_utx5q5_k$(item))
        result = true;
    }
    return result;
  }
}
function removeAll(_this__u8e3s4, predicate) {
  return filterInPlace(_this__u8e3s4, predicate, true);
}
function filterInPlace(_this__u8e3s4, predicate, predicateResultToRemove) {
  if (!isInterface(_this__u8e3s4, RandomAccess)) {
    return filterInPlace_0(isInterface(_this__u8e3s4, MutableIterable) ? _this__u8e3s4 : THROW_CCE(), predicate, predicateResultToRemove);
  }
  var writeIndex = 0;
  var inductionVariable = 0;
  var last = get_lastIndex_0(_this__u8e3s4);
  if (inductionVariable <= last)
    $l$loop: do {
      var readIndex = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      var element = _this__u8e3s4.get_c1px32_k$(readIndex);
      if (predicate(element) === predicateResultToRemove)
        continue $l$loop;
      if (!(writeIndex === readIndex)) {
        _this__u8e3s4.set_82063s_k$(writeIndex, element);
      }
      writeIndex = writeIndex + 1 | 0;
    }
     while (!(readIndex === last));
  if (writeIndex < _this__u8e3s4.get_size_woubt6_k$()) {
    var inductionVariable_0 = get_lastIndex_0(_this__u8e3s4);
    var last_0 = writeIndex;
    if (last_0 <= inductionVariable_0)
      do {
        var removeIndex = inductionVariable_0;
        inductionVariable_0 = inductionVariable_0 + -1 | 0;
        _this__u8e3s4.removeAt_6niowx_k$(removeIndex);
      }
       while (!(removeIndex === last_0));
    return true;
  } else {
    return false;
  }
}
function filterInPlace_0(_this__u8e3s4, predicate, predicateResultToRemove) {
  var result = false;
  // Inline function 'kotlin.with' call
  var $this$with = _this__u8e3s4.iterator_jk1svi_k$();
  while ($this$with.hasNext_bitz1p_k$())
    if (predicate($this$with.next_20eer_k$()) === predicateResultToRemove) {
      $this$with.remove_ldkf9o_k$();
      result = true;
    }
  return result;
}
var EmptySet_instance;
function EmptySet_getInstance() {
  return EmptySet_instance;
}
function startCoroutine(_this__u8e3s4, completion) {
  // Inline function 'kotlin.coroutines.resume' call
  var this_0 = intercepted(createCoroutineUninterceptedGeneratorVersion(_this__u8e3s4, completion));
  // Inline function 'kotlin.Companion.success' call
  var tmp$ret$0 = _Result___init__impl__xyqfz8(Unit_instance);
  this_0.resumeWith_dtxwbr_k$(tmp$ret$0);
}
var Key_instance;
function Key_getInstance() {
  return Key_instance;
}
var EmptyCoroutineContext_instance;
function EmptyCoroutineContext_getInstance() {
  return EmptyCoroutineContext_instance;
}
function get_COROUTINE_SUSPENDED() {
  return CoroutineSingletons_COROUTINE_SUSPENDED_getInstance();
}
var CoroutineSingletons_COROUTINE_SUSPENDED_instance;
var CoroutineSingletons_UNDECIDED_instance;
var CoroutineSingletons_RESUMED_instance;
var CoroutineSingletons_entriesInitialized;
function CoroutineSingletons_initEntries() {
  if (CoroutineSingletons_entriesInitialized)
    return Unit_instance;
  CoroutineSingletons_entriesInitialized = true;
  CoroutineSingletons_COROUTINE_SUSPENDED_instance = new CoroutineSingletons('COROUTINE_SUSPENDED', 0);
  CoroutineSingletons_UNDECIDED_instance = new CoroutineSingletons('UNDECIDED', 1);
  CoroutineSingletons_RESUMED_instance = new CoroutineSingletons('RESUMED', 2);
}
function CoroutineSingletons_COROUTINE_SUSPENDED_getInstance() {
  CoroutineSingletons_initEntries();
  return CoroutineSingletons_COROUTINE_SUSPENDED_instance;
}
function CoroutineSingletons_UNDECIDED_getInstance() {
  CoroutineSingletons_initEntries();
  return CoroutineSingletons_UNDECIDED_instance;
}
function CoroutineSingletons_RESUMED_getInstance() {
  CoroutineSingletons_initEntries();
  return CoroutineSingletons_RESUMED_instance;
}
function appendElement(_this__u8e3s4, element, transform) {
  if (!(transform == null))
    _this__u8e3s4.append_jgojdo_k$(transform(element));
  else {
    if (element == null ? true : isCharSequence(element))
      _this__u8e3s4.append_jgojdo_k$(element);
    else {
      if (element instanceof Char)
        _this__u8e3s4.append_t84oo1_k$(element.value_1);
      else {
        _this__u8e3s4.append_jgojdo_k$(toString_1(element));
      }
    }
  }
}
function get_BYTE_TO_LOWER_CASE_HEX_DIGITS() {
  _init_properties_HexExtensions_kt__wu8rc3();
  return BYTE_TO_LOWER_CASE_HEX_DIGITS;
}
var BYTE_TO_LOWER_CASE_HEX_DIGITS;
var BYTE_TO_UPPER_CASE_HEX_DIGITS;
var HEX_DIGITS_TO_DECIMAL;
var HEX_DIGITS_TO_LONG_DECIMAL;
var properties_initialized_HexExtensions_kt_h16sbl;
function _init_properties_HexExtensions_kt__wu8rc3() {
  if (!properties_initialized_HexExtensions_kt_h16sbl) {
    properties_initialized_HexExtensions_kt_h16sbl = true;
    var tmp = 0;
    var tmp_0 = new Int32Array(256);
    while (tmp < 256) {
      var tmp_1 = tmp;
      // Inline function 'kotlin.code' call
      var this_0 = charCodeAt('0123456789abcdef', tmp_1 >> 4);
      var tmp_2 = Char__toInt_impl_vasixd(this_0) << 8;
      // Inline function 'kotlin.code' call
      var this_1 = charCodeAt('0123456789abcdef', tmp_1 & 15);
      tmp_0[tmp_1] = tmp_2 | Char__toInt_impl_vasixd(this_1);
      tmp = tmp + 1 | 0;
    }
    BYTE_TO_LOWER_CASE_HEX_DIGITS = tmp_0;
    var tmp_3 = 0;
    var tmp_4 = new Int32Array(256);
    while (tmp_3 < 256) {
      var tmp_5 = tmp_3;
      // Inline function 'kotlin.code' call
      var this_2 = charCodeAt('0123456789ABCDEF', tmp_5 >> 4);
      var tmp_6 = Char__toInt_impl_vasixd(this_2) << 8;
      // Inline function 'kotlin.code' call
      var this_3 = charCodeAt('0123456789ABCDEF', tmp_5 & 15);
      tmp_4[tmp_5] = tmp_6 | Char__toInt_impl_vasixd(this_3);
      tmp_3 = tmp_3 + 1 | 0;
    }
    BYTE_TO_UPPER_CASE_HEX_DIGITS = tmp_4;
    var tmp_7 = 0;
    var tmp_8 = new Int32Array(256);
    while (tmp_7 < 256) {
      tmp_8[tmp_7] = -1;
      tmp_7 = tmp_7 + 1 | 0;
    }
    // Inline function 'kotlin.apply' call
    // Inline function 'kotlin.text.forEachIndexed' call
    var index = 0;
    var indexedObject = '0123456789abcdef';
    var inductionVariable = 0;
    while (inductionVariable < charSequenceLength(indexedObject)) {
      var item = charSequenceGet(indexedObject, inductionVariable);
      inductionVariable = inductionVariable + 1 | 0;
      var _unary__edvuaz = index;
      index = _unary__edvuaz + 1 | 0;
      // Inline function 'kotlin.code' call
      tmp_8[Char__toInt_impl_vasixd(item)] = _unary__edvuaz;
    }
    // Inline function 'kotlin.text.forEachIndexed' call
    var index_0 = 0;
    var indexedObject_0 = '0123456789ABCDEF';
    var inductionVariable_0 = 0;
    while (inductionVariable_0 < charSequenceLength(indexedObject_0)) {
      var item_0 = charSequenceGet(indexedObject_0, inductionVariable_0);
      inductionVariable_0 = inductionVariable_0 + 1 | 0;
      var _unary__edvuaz_0 = index_0;
      index_0 = _unary__edvuaz_0 + 1 | 0;
      // Inline function 'kotlin.code' call
      tmp_8[Char__toInt_impl_vasixd(item_0)] = _unary__edvuaz_0;
    }
    HEX_DIGITS_TO_DECIMAL = tmp_8;
    var tmp_9 = 0;
    var tmp_10 = new BigInt64Array(256);
    while (tmp_9 < 256) {
      tmp_10[tmp_9] = -1n;
      tmp_9 = tmp_9 + 1 | 0;
    }
    // Inline function 'kotlin.apply' call
    // Inline function 'kotlin.text.forEachIndexed' call
    var index_1 = 0;
    var indexedObject_1 = '0123456789abcdef';
    var inductionVariable_1 = 0;
    while (inductionVariable_1 < charSequenceLength(indexedObject_1)) {
      var item_1 = charSequenceGet(indexedObject_1, inductionVariable_1);
      inductionVariable_1 = inductionVariable_1 + 1 | 0;
      var _unary__edvuaz_1 = index_1;
      index_1 = _unary__edvuaz_1 + 1 | 0;
      // Inline function 'kotlin.code' call
      tmp_10[Char__toInt_impl_vasixd(item_1)] = fromInt(_unary__edvuaz_1);
    }
    // Inline function 'kotlin.text.forEachIndexed' call
    var index_2 = 0;
    var indexedObject_2 = '0123456789ABCDEF';
    var inductionVariable_2 = 0;
    while (inductionVariable_2 < charSequenceLength(indexedObject_2)) {
      var item_2 = charSequenceGet(indexedObject_2, inductionVariable_2);
      inductionVariable_2 = inductionVariable_2 + 1 | 0;
      var _unary__edvuaz_2 = index_2;
      index_2 = _unary__edvuaz_2 + 1 | 0;
      // Inline function 'kotlin.code' call
      tmp_10[Char__toInt_impl_vasixd(item_2)] = fromInt(_unary__edvuaz_2);
    }
    HEX_DIGITS_TO_LONG_DECIMAL = tmp_10;
  }
}
function isBlank(_this__u8e3s4) {
  var tmp$ret$1;
  $l$block: {
    // Inline function 'kotlin.text.all' call
    var inductionVariable = 0;
    while (inductionVariable < charSequenceLength(_this__u8e3s4)) {
      var element = charSequenceGet(_this__u8e3s4, inductionVariable);
      inductionVariable = inductionVariable + 1 | 0;
      if (!isWhitespace(element)) {
        tmp$ret$1 = false;
        break $l$block;
      }
    }
    tmp$ret$1 = true;
  }
  return tmp$ret$1;
}
function _Result___init__impl__xyqfz8(value) {
  return value;
}
function _Result___get_value__impl__bjfvqg($this) {
  return $this;
}
function _Result___get_isSuccess__impl__sndoy8($this) {
  var tmp = _Result___get_value__impl__bjfvqg($this);
  return !(tmp instanceof Failure);
}
function _Result___get_isFailure__impl__jpiriv($this) {
  var tmp = _Result___get_value__impl__bjfvqg($this);
  return tmp instanceof Failure;
}
function Result__exceptionOrNull_impl_p6xea9($this) {
  var tmp;
  if (_Result___get_value__impl__bjfvqg($this) instanceof Failure) {
    tmp = _Result___get_value__impl__bjfvqg($this).exception_1;
  } else {
    tmp = null;
  }
  return tmp;
}
var Companion_instance_9;
function Companion_getInstance_9() {
  return Companion_instance_9;
}
function createFailure(exception) {
  return new Failure(exception);
}
function to(_this__u8e3s4, that) {
  return new Pair(_this__u8e3s4, that);
}
var Companion_instance_10;
function Companion_getInstance_10() {
  if (Companion_instance_10 === VOID)
    new Companion_10();
  return Companion_instance_10;
}
function truncateForErrorMessage(_this__u8e3s4, maxSize) {
  return joinToString(_this__u8e3s4, VOID, '[', ']', maxSize);
}
function secureRandomUuid() {
  // Inline function 'kotlin.also' call
  var this_0 = new Int8Array(16);
  secureRandomBytes(this_0);
  return uuidFromRandomBytes(this_0);
}
function uuidFromRandomBytes(randomBytes) {
  randomBytes[6] = toByte(randomBytes[6] & 15);
  randomBytes[6] = toByte(randomBytes[6] | 64);
  randomBytes[8] = toByte(randomBytes[8] & 63);
  randomBytes[8] = toByte(randomBytes[8] | 128);
  return Companion_getInstance_10().fromByteArray_d3r0u1_k$(randomBytes);
}
function _ULong___init__impl__c78o9k(data) {
  return data;
}
function _ULong___get_data__impl__fggpzb($this) {
  return $this;
}
function _UShort___init__impl__jigrne(data) {
  return data;
}
function _UShort___get_data__impl__g0245($this) {
  return $this;
}
var None_instance;
function None_getInstance() {
  if (None_instance === VOID)
    new None();
  return None_instance;
}
function atomic$int$1(initial) {
  return atomic$int$(initial, None_getInstance());
}
function atomic$int$(initial, trace) {
  trace = trace === VOID ? None_getInstance() : trace;
  return new AtomicInt(initial);
}
function *_generator_suspended$suspendBridge__ucnhzs($this, fn, $completion) {
  var tmp;
  if ($this.suspended === protoOf(Edge).suspended) {
    var tmp_0 = $this.suspended_n6q0t5_k$(fn, $completion);
    if (tmp_0 === get_COROUTINE_SUSPENDED())
      tmp_0 = yield tmp_0;
    tmp = tmp_0;
  } else {
    var tmp_1 = await_0($this.suspended(fn), $completion);
    if (tmp_1 === get_COROUTINE_SUSPENDED())
      tmp_1 = yield tmp_1;
    tmp = tmp_1;
  }
  return tmp;
}
function *_generator_suspended__vg2ce1($this, fn, $completion) {
  // Inline function 'dev.shibasis.reaktor.portgraph.port.ProviderPort.suspended' call
  var tmp = fn($this.provider.impl, $completion);
  if (tmp === get_COROUTINE_SUSPENDED())
    tmp = yield tmp;
  return tmp;
}
function connectPort(consumerPort, providerPort) {
  return connect(consumerPort, providerPort);
}
function connectNode(node1, node2) {
  connectConsumerProvider(node1, node2);
  connectConsumerProvider(node2, node1);
}
function connect(consumerPort, providerPort) {
  if (!consumerPort.type.equals(providerPort.type)) {
    var error = 'Incompatible ports: consumer -> ' + consumerPort.type.toString() + ', provider -> ' + providerPort.type.toString();
    // Inline function 'kotlin.Companion.failure' call
    var exception = IllegalArgumentException.new_kotlin_IllegalArgumentException_sfqr8_k$(error);
    return _Result___init__impl__xyqfz8(createFailure(exception));
  }
  if (consumerPort.isConnected()) {
    var error_0 = 'Consumer is already connected, ' + consumerPort.toString();
    // Inline function 'kotlin.Companion.failure' call
    var exception_0 = IllegalStateException.new_kotlin_IllegalStateException_w47ei6_k$(error_0);
    return _Result___init__impl__xyqfz8(createFailure(exception_0));
  }
  var source = consumerPort.owner;
  var destination = providerPort.owner;
  // Inline function 'kotlin.Companion.success' call
  var value = new Edge(source, consumerPort, destination, providerPort);
  return _Result___init__impl__xyqfz8(value);
}
function connectPorts(consumers, providers) {
  if (consumers.get_size_woubt6_k$() === 1 && providers.get_size_woubt6_k$() === 1 && first(consumers.get_values_ksazhn_k$()).type.equals(first(providers.get_values_ksazhn_k$()).type)) {
    // Inline function 'kotlin.map' call
    var this_0 = connect(first(consumers.get_values_ksazhn_k$()), first(providers.get_values_ksazhn_k$()));
    var tmp;
    if (_Result___get_isSuccess__impl__sndoy8(this_0)) {
      var tmp_0 = _Result___get_value__impl__bjfvqg(this_0);
      var it = (tmp_0 == null ? true : !(tmp_0 == null)) ? tmp_0 : THROW_CCE();
      // Inline function 'kotlin.Companion.success' call
      var value = listOf(it);
      tmp = _Result___init__impl__xyqfz8(value);
    } else {
      tmp = _Result___init__impl__xyqfz8(_Result___get_value__impl__bjfvqg(this_0));
    }
    return tmp;
  }
  // Inline function 'kotlin.collections.mapNotNull' call
  var tmp0 = intersect(consumers.get_keys_wop4xp_k$(), providers.get_keys_wop4xp_k$());
  // Inline function 'kotlin.collections.mapNotNullTo' call
  var destination = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
  // Inline function 'kotlin.collections.forEach' call
  var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var element = _iterator__ex2g4s.next_20eer_k$();
    var tmp_1 = consumers.get_wei43m_k$(element);
    var consumer = tmp_1 instanceof ConsumerPort ? tmp_1 : THROW_CCE();
    var tmp_2 = providers.get_wei43m_k$(element);
    var provider = tmp_2 instanceof ProviderPort ? tmp_2 : THROW_CCE();
    var tmp_3;
    if (consumer.type.equals(provider.type)) {
      // Inline function 'kotlin.Result.getOrNull' call
      var this_1 = connect(consumer, provider);
      var tmp_4;
      if (_Result___get_isFailure__impl__jpiriv(this_1)) {
        tmp_4 = null;
      } else {
        var tmp_5 = _Result___get_value__impl__bjfvqg(this_1);
        tmp_4 = (tmp_5 == null ? true : !(tmp_5 == null)) ? tmp_5 : THROW_CCE();
      }
      tmp_3 = tmp_4;
    } else {
      tmp_3 = null;
    }
    var tmp0_safe_receiver = tmp_3;
    if (tmp0_safe_receiver == null)
      null;
    else {
      // Inline function 'kotlin.let' call
      destination.add_utx5q5_k$(tmp0_safe_receiver);
    }
  }
  var edges = destination;
  // Inline function 'kotlin.Companion.success' call
  return _Result___init__impl__xyqfz8(edges);
}
function connectConsumerProvider(consumerNode, providerNode) {
  var consumerTypes = consumerNode.consumerPorts.get_keys_wop4xp_k$();
  var providerTypes = providerNode.providerPorts.get_keys_wop4xp_k$();
  // Inline function 'kotlin.collections.forEach' call
  var _iterator__ex2g4s = intersect(consumerTypes, providerTypes).iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var element = _iterator__ex2g4s.next_20eer_k$();
    var tmp0_elvis_lhs = consumerNode.consumerPorts.get_wei43m_k$(element);
    var tmp;
    if (tmp0_elvis_lhs == null) {
      // Inline function 'kotlin.collections.mapOf' call
      tmp = emptyMap();
    } else {
      tmp = tmp0_elvis_lhs;
    }
    var tmp_0 = tmp;
    var tmp1_elvis_lhs = providerNode.providerPorts.get_wei43m_k$(element);
    var tmp_1;
    if (tmp1_elvis_lhs == null) {
      // Inline function 'kotlin.collections.mapOf' call
      tmp_1 = emptyMap();
    } else {
      tmp_1 = tmp1_elvis_lhs;
    }
    connectPorts(tmp_0, tmp_1);
  }
}
function *_generator_suspended$suspendBridge__ucnhzs_0($this, fn, $completion) {
  var tmp;
  if ($this.suspended === protoOf(ConsumerPort).suspended) {
    var tmp_0 = $this.suspended_n1fheg_k$(fn, $completion);
    if (tmp_0 === get_COROUTINE_SUSPENDED())
      tmp_0 = yield tmp_0;
    tmp = tmp_0;
  } else {
    var tmp_1 = await_0($this.suspended(fn), $completion);
    if (tmp_1 === get_COROUTINE_SUSPENDED())
      tmp_1 = yield tmp_1;
    tmp = tmp_1;
  }
  return tmp;
}
function registerConsumer_0(_this__u8e3s4, key, type) {
  // Inline function 'kotlin.collections.getOrPut' call
  var this_0 = _this__u8e3s4.consumerPorts;
  var value = this_0.get_wei43m_k$(type);
  var tmp;
  if (value == null) {
    // Inline function 'kotlin.collections.linkedMapOf' call
    var answer = LinkedHashMap.new_kotlin_collections_LinkedHashMap_ga0any_k$();
    this_0.put_4fpzoq_k$(type, answer);
    tmp = answer;
  } else {
    tmp = value;
  }
  // Inline function 'kotlin.collections.getOrPut' call
  var this_1 = tmp;
  var value_0 = this_1.get_wei43m_k$(key);
  var tmp_0;
  if (value_0 == null) {
    var answer_0 = ConsumerPort.new_dev_shibasis_reaktor_portgraph_port_ConsumerPort_931x6c_k$(_this__u8e3s4, key, type);
    this_1.put_4fpzoq_k$(key, answer_0);
    tmp_0 = answer_0;
  } else {
    tmp_0 = value_0;
  }
  var tmp_1 = tmp_0;
  return tmp_1 instanceof ConsumerPort ? tmp_1 : THROW_CCE();
}
function getConsumer_0(_this__u8e3s4, key, type) {
  // Inline function 'kotlin.collections.getOrPut' call
  var this_0 = _this__u8e3s4.consumerPorts;
  var value = this_0.get_wei43m_k$(type);
  var tmp;
  if (value == null) {
    // Inline function 'kotlin.collections.linkedMapOf' call
    var answer = LinkedHashMap.new_kotlin_collections_LinkedHashMap_ga0any_k$();
    this_0.put_4fpzoq_k$(type, answer);
    tmp = answer;
  } else {
    tmp = value;
  }
  var tmp_0 = tmp.get_wei43m_k$(key);
  return tmp_0 instanceof ConsumerPort ? tmp_0 : null;
}
function get__sequence() {
  _init_properties_Port_kt__lbdmcf();
  return _sequence;
}
var _sequence;
var Companion_instance_11;
function Companion_getInstance_11() {
  return Companion_instance_11;
}
var Companion_instance_12;
function Companion_getInstance_12() {
  return Companion_instance_12;
}
function invoke(key, type) {
  return Companion_instance_12.invoke_h1q7yg_k$(key, type);
}
function name(_this__u8e3s4) {
  _init_properties_Port_kt__lbdmcf();
  var simple = _this__u8e3s4.get_simpleName_r6f8py_k$();
  // Inline function 'kotlin.text.isNullOrBlank' call
  if (!(simple == null || isBlank(simple)))
    return simple;
  return 'anonymous_' + get__sequence().atomicfu$getAndIncrement();
}
var properties_initialized_Port_kt_3hpecj;
function _init_properties_Port_kt__lbdmcf() {
  if (!properties_initialized_Port_kt_3hpecj) {
    properties_initialized_Port_kt_3hpecj = true;
    _sequence = atomic$int$1(0);
  }
}
function *_generator_suspended$suspendBridge__ucnhzs_1($this, fn, $completion) {
  var tmp;
  if ($this.suspended === protoOf(ProviderPort).suspended) {
    var tmp_0 = $this.suspended_n1fheg_k$(fn, $completion);
    if (tmp_0 === get_COROUTINE_SUSPENDED())
      tmp_0 = yield tmp_0;
    tmp = tmp_0;
  } else {
    var tmp_1 = await_0($this.suspended(fn), $completion);
    if (tmp_1 === get_COROUTINE_SUSPENDED())
      tmp_1 = yield tmp_1;
    tmp = tmp_1;
  }
  return tmp;
}
function registerProvider_0(_this__u8e3s4, key, type, impl) {
  // Inline function 'kotlin.collections.getOrPut' call
  var this_0 = _this__u8e3s4.providerPorts;
  var value = this_0.get_wei43m_k$(type);
  var tmp;
  if (value == null) {
    // Inline function 'kotlin.collections.linkedMapOf' call
    var answer = LinkedHashMap.new_kotlin_collections_LinkedHashMap_ga0any_k$();
    this_0.put_4fpzoq_k$(type, answer);
    tmp = answer;
  } else {
    tmp = value;
  }
  // Inline function 'kotlin.collections.getOrPut' call
  var this_1 = tmp;
  var value_0 = this_1.get_wei43m_k$(key);
  var tmp_0;
  if (value_0 == null) {
    var answer_0 = ProviderPort.new_dev_shibasis_reaktor_portgraph_port_ProviderPort_l7jdif_k$(_this__u8e3s4, key, type, impl);
    this_1.put_4fpzoq_k$(key, answer_0);
    tmp_0 = answer_0;
  } else {
    tmp_0 = value_0;
  }
  var tmp_1 = tmp_0;
  return tmp_1 instanceof ProviderPort ? tmp_1 : THROW_CCE();
}
function getProvider_0(_this__u8e3s4, key, type) {
  // Inline function 'kotlin.collections.getOrPut' call
  var this_0 = _this__u8e3s4.providerPorts;
  var value = this_0.get_wei43m_k$(type);
  var tmp;
  if (value == null) {
    // Inline function 'kotlin.collections.linkedMapOf' call
    var answer = LinkedHashMap.new_kotlin_collections_LinkedHashMap_ga0any_k$();
    this_0.put_4fpzoq_k$(type, answer);
    tmp = answer;
  } else {
    tmp = value;
  }
  var tmp_0 = tmp.get_wei43m_k$(key);
  return tmp_0 instanceof ProviderPort ? tmp_0 : null;
}
var StructuralSelector_instance;
function StructuralSelector_getInstance() {
  return StructuralSelector_instance;
}
var ConnectivitySelector_instance;
function ConnectivitySelector_getInstance() {
  return ConnectivitySelector_instance;
}
function traverseRecursive($this, current, selector, visitor, visited) {
  if (!visited.add_utx5q5_k$(current))
    return Unit_instance;
  var onExit = visitor.visit(current);
  // Inline function 'kotlin.collections.forEach' call
  var _iterator__ex2g4s = selector.neighbors(current).iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var element = _iterator__ex2g4s.next_20eer_k$();
    traverseRecursive(DepthFirstTraverser_instance, element, selector, visitor, visited);
  }
  onExit();
}
var DepthFirstTraverser_instance;
function DepthFirstTraverser_getInstance() {
  return DepthFirstTraverser_instance;
}
var BreadthFirstTraverser_instance;
function BreadthFirstTraverser_getInstance() {
  return BreadthFirstTraverser_instance;
}
function PortGraphVisitor$NoOpExit$lambda() {
  return Unit_instance;
}
function getElementLabel($this, visitable) {
  var tmp;
  if (visitable instanceof PortGraph) {
    // Inline function 'kotlin.text.ifEmpty' call
    var this_0 = visitable.label;
    var tmp_0;
    // Inline function 'kotlin.text.isEmpty' call
    if (charSequenceLength(this_0) === 0) {
      tmp_0 = visitable.id.toString();
    } else {
      tmp_0 = this_0;
    }
    tmp = '[PortGraph] ' + tmp_0;
  } else {
    if (visitable instanceof PortNode) {
      // Inline function 'kotlin.text.ifEmpty' call
      var this_1 = visitable.label;
      var tmp_1;
      // Inline function 'kotlin.text.isEmpty' call
      if (charSequenceLength(this_1) === 0) {
        tmp_1 = visitable.id.toString();
      } else {
        tmp_1 = this_1;
      }
      tmp = '[PortNode] ' + tmp_1;
    } else {
      if (visitable instanceof ConsumerPort) {
        tmp = '[ConsumerPort] ' + visitable.key.toString();
      } else {
        if (visitable instanceof ProviderPort) {
          tmp = '[ProviderPort] ' + visitable.key.toString();
        } else {
          if (visitable instanceof Edge) {
            tmp = '[Edge] ' + take(visitable.id.toString(), 8) + '...';
          } else {
            tmp = '[Visitable] ' + hashCode(visitable);
          }
        }
      }
    }
  }
  return tmp;
}
function processVisit($this, visitable) {
  var currentMap = mutableMapOf([to('element', getElementLabel($this, visitable))]);
  // Inline function 'kotlin.collections.isNotEmpty' call
  if (!$this.mapStack_1.isEmpty_y1axqb_k$()) {
    var parentMap = $this.mapStack_1.last_1z1cm_k$();
    // Inline function 'kotlin.collections.getOrPut' call
    var key = 'children';
    var value = parentMap.get_wei43m_k$(key);
    var tmp;
    if (value == null) {
      // Inline function 'kotlin.collections.mutableListOf' call
      var answer = ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$();
      parentMap.put_4fpzoq_k$(key, answer);
      tmp = answer;
    } else {
      tmp = value;
    }
    var tmp_0 = tmp;
    var childrenList = isInterface(tmp_0, KtMutableList) ? tmp_0 : THROW_CCE();
    childrenList.add_utx5q5_k$(currentMap);
  } else {
    $this.rootMap_1 = currentMap;
  }
  $this.mapStack_1.add_utx5q5_k$(currentMap);
  return HierarchyVisitor$processVisit$lambda($this);
}
function HierarchyVisitor$processVisit$lambda(this$0) {
  return () => {
    this$0.mapStack_1.removeLast_i5wx8a_k$();
    return Unit_instance;
  };
}
//region block: post-declaration
initMetadataForInterface(CharSequence, 'CharSequence');
initMetadataForClass(Char, 'Char');
initMetadataForCompanion(Companion);
initMetadataForInterface(Collection, 'Collection');
initMetadataForInterface(KtList, 'List', VOID, VOID, [Collection]);
initMetadataForInterface(Entry, 'Entry');
initMetadataForInterface(KtMap, 'Map');
initMetadataForInterface(KtSet, 'Set', VOID, VOID, [Collection]);
initMetadataForCompanion(Companion_0);
initMetadataForInterface(KtMutableMap, 'MutableMap', VOID, VOID, [KtMap]);
initMetadataForCompanion(Companion_1);
initMetadataForInterface(MutableIterable, 'MutableIterable');
initMetadataForInterface(KtMutableList, 'MutableList', VOID, VOID, [KtList, Collection, MutableIterable]);
initMetadataForCompanion(Companion_2);
initMetadataForClass(Enum, 'Enum');
initMetadataForInterface(FunctionAdapter, 'FunctionAdapter');
initMetadataForClass(JsArrayView, 'JsArrayView');
initMetadataForClass(JsMapView, 'JsMapView', JsMapView);
initMetadataForInterface(Comparator, 'Comparator');
initMetadataForObject(Unit, 'Unit');
initMetadataForClass(AbstractCollection, 'AbstractCollection', VOID, VOID, [Collection]);
initMetadataForClass(AbstractMutableCollection, 'AbstractMutableCollection', VOID, VOID, [AbstractCollection, Collection, MutableIterable]);
initMetadataForClass(IteratorImpl, 'IteratorImpl');
initMetadataForClass(ListIteratorImpl, 'ListIteratorImpl');
protoOf(AbstractMutableList).asJsArrayView = asJsArrayView;
protoOf(AbstractMutableList).asJsReadonlyArrayView = asJsReadonlyArrayView;
initMetadataForClass(AbstractMutableList, 'AbstractMutableList', VOID, VOID, [AbstractMutableCollection, KtMutableList]);
initMetadataForInterface(RandomAccess, 'RandomAccess');
initMetadataForClass(SubList, 'SubList', VOID, VOID, [AbstractMutableList, RandomAccess]);
protoOf(AbstractMap).asJsReadonlyMapView_6h4p3w_k$ = asJsReadonlyMapView;
initMetadataForClass(AbstractMap, 'AbstractMap', VOID, VOID, [KtMap]);
protoOf(AbstractMutableMap).asJsMapView = asJsMapView;
initMetadataForClass(AbstractMutableMap, 'AbstractMutableMap', VOID, VOID, [AbstractMap, KtMutableMap]);
initMetadataForClass(AbstractMutableSet, 'AbstractMutableSet', VOID, VOID, [AbstractMutableCollection, KtSet, Collection, MutableIterable]);
initMetadataForCompanion(Companion_3);
initMetadataForClass(ArrayList, 'ArrayList', ArrayList.new_kotlin_collections_ArrayList_ony0vx_k$, VOID, [AbstractMutableList, KtMutableList, RandomAccess]);
initMetadataForClass(HashMap, 'HashMap', HashMap.new_kotlin_collections_HashMap_2a5kxx_k$, VOID, [AbstractMutableMap, KtMutableMap]);
initMetadataForClass(HashMapKeys, 'HashMapKeys', VOID, VOID, [KtSet, Collection, MutableIterable, AbstractMutableSet]);
initMetadataForClass(HashMapValues, 'HashMapValues', VOID, VOID, [Collection, MutableIterable, AbstractMutableCollection]);
initMetadataForClass(HashMapEntrySetBase, 'HashMapEntrySetBase', VOID, VOID, [KtSet, Collection, MutableIterable, AbstractMutableSet]);
initMetadataForClass(HashMapEntrySet, 'HashMapEntrySet');
initMetadataForClass(HashMapKeysDefault$iterator$1);
initMetadataForClass(HashMapKeysDefault, 'HashMapKeysDefault');
initMetadataForClass(HashMapValuesDefault$iterator$1);
initMetadataForClass(HashMapValuesDefault, 'HashMapValuesDefault');
initMetadataForClass(HashSet, 'HashSet', HashSet.new_kotlin_collections_HashSet_ovxcsm_k$, VOID, [AbstractMutableSet, KtSet, Collection, MutableIterable]);
initMetadataForCompanion(Companion_4);
initMetadataForClass(Itr, 'Itr');
initMetadataForClass(KeysItr, 'KeysItr');
initMetadataForClass(ValuesItr, 'ValuesItr');
initMetadataForClass(EntriesItr, 'EntriesItr');
initMetadataForClass(EntryRef, 'EntryRef', VOID, VOID, [Entry]);
initMetadataForInterface(InternalMap, 'InternalMap');
protoOf(InternalHashMap).containsAllEntries_m9iqdx_k$ = containsAllEntries;
initMetadataForClass(InternalHashMap, 'InternalHashMap', InternalHashMap.new_kotlin_collections_InternalHashMap_iefrky_k$, VOID, [InternalMap]);
initMetadataForClass(LinkedHashMap, 'LinkedHashMap', LinkedHashMap.new_kotlin_collections_LinkedHashMap_ga0any_k$, VOID, [HashMap, KtMutableMap]);
initMetadataForClass(LinkedHashSet, 'LinkedHashSet', LinkedHashSet.new_kotlin_collections_LinkedHashSet_ahyf7j_k$, VOID, [HashSet, KtSet, Collection, MutableIterable]);
initMetadataForObject(CompletedContinuation, 'CompletedContinuation');
initMetadataForClass(InterceptedCoroutine, 'InterceptedCoroutine');
initMetadataForClass(GeneratorCoroutineImpl, 'GeneratorCoroutineImpl');
initMetadataForClass(SafeContinuation, 'SafeContinuation');
initMetadataForClass(promisify$2$$inlined$Continuation$1);
initMetadataForClass(Exception, 'Exception', Exception.new_kotlin_Exception_f32mds_k$);
initMetadataForClass(RuntimeException, 'RuntimeException', RuntimeException.new_kotlin_RuntimeException_29f9zq_k$);
initMetadataForClass(UnsupportedOperationException, 'UnsupportedOperationException', UnsupportedOperationException.new_kotlin_UnsupportedOperationException_cv3bvm_k$);
initMetadataForClass(IllegalStateException, 'IllegalStateException', IllegalStateException.new_kotlin_IllegalStateException_1wtnp1_k$);
initMetadataForClass(IllegalArgumentException, 'IllegalArgumentException', IllegalArgumentException.new_kotlin_IllegalArgumentException_pv5o3f_k$);
initMetadataForClass(NoSuchElementException, 'NoSuchElementException', NoSuchElementException.new_kotlin_NoSuchElementException_wy3d4q_k$);
initMetadataForClass(IndexOutOfBoundsException, 'IndexOutOfBoundsException', IndexOutOfBoundsException.new_kotlin_IndexOutOfBoundsException_cc7xqw_k$);
initMetadataForClass(ConcurrentModificationException, 'ConcurrentModificationException', ConcurrentModificationException.new_kotlin_ConcurrentModificationException_fy07nh_k$);
initMetadataForClass(Error_0, 'Error', Error_0.new_kotlin_Error_8ce653_k$);
initMetadataForClass(ArithmeticException, 'ArithmeticException', ArithmeticException.new_kotlin_ArithmeticException_t7nj4q_k$);
initMetadataForClass(NullPointerException, 'NullPointerException', NullPointerException.new_kotlin_NullPointerException_q6jd54_k$);
initMetadataForClass(UninitializedPropertyAccessException, 'UninitializedPropertyAccessException', UninitializedPropertyAccessException.new_kotlin_UninitializedPropertyAccessException_l975ei_k$);
initMetadataForClass(ClassCastException, 'ClassCastException', ClassCastException.new_kotlin_ClassCastException_zhuhe1_k$);
initMetadataForInterface(KClass, 'KClass');
initMetadataForClass(KClassImpl, 'KClassImpl', VOID, VOID, [KClass]);
initMetadataForClass(PrimitiveKClassImpl, 'PrimitiveKClassImpl');
initMetadataForObject(NothingKClassImpl, 'NothingKClassImpl');
initMetadataForClass(SimpleKClassImpl, 'SimpleKClassImpl');
initMetadataForObject(PrimitiveClasses, 'PrimitiveClasses');
initMetadataForClass(CharacterCodingException, 'CharacterCodingException', CharacterCodingException.new_kotlin_text_CharacterCodingException_el5v5_k$);
initMetadataForClass(StringBuilder, 'StringBuilder', StringBuilder.new_kotlin_text_StringBuilder_u46mrb_k$, VOID, [CharSequence]);
initMetadataForClass(sam$kotlin_Comparator$0, 'sam$kotlin_Comparator$0', VOID, VOID, [Comparator, FunctionAdapter]);
initMetadataForCompanion(Companion_5);
initMetadataForClass(AbstractMap$keys$1$iterator$1);
initMetadataForClass(AbstractMap$values$1$iterator$1);
initMetadataForCompanion(Companion_6);
initMetadataForClass(AbstractSet, 'AbstractSet', VOID, VOID, [AbstractCollection, KtSet]);
initMetadataForClass(AbstractMap$keys$1);
initMetadataForClass(AbstractMap$values$1);
initMetadataForCompanion(Companion_7);
initMetadataForCompanion(Companion_8);
initMetadataForClass(ArrayDeque, 'ArrayDeque', ArrayDeque.new_kotlin_collections_ArrayDeque_sf0swv_k$);
protoOf(EmptyList).asJsReadonlyArrayView = asJsReadonlyArrayView;
initMetadataForObject(EmptyList, 'EmptyList', VOID, VOID, [KtList, RandomAccess]);
initMetadataForObject(EmptyIterator, 'EmptyIterator');
initMetadataForObject(EmptyMap, 'EmptyMap', VOID, VOID, [KtMap]);
initMetadataForObject(EmptySet, 'EmptySet', VOID, VOID, [KtSet]);
initMetadataForObject(Key, 'Key');
initMetadataForInterface(ContinuationInterceptor, 'ContinuationInterceptor');
initMetadataForObject(EmptyCoroutineContext, 'EmptyCoroutineContext');
initMetadataForClass(CoroutineSingletons, 'CoroutineSingletons');
initMetadataForCompanion(Companion_9);
initMetadataForClass(Failure, 'Failure');
initMetadataForClass(NotImplementedError, 'NotImplementedError', NotImplementedError.new_kotlin_NotImplementedError_8rzvsv_k$);
initMetadataForClass(Pair, 'Pair');
initMetadataForCompanion(Companion_10);
initMetadataForClass(Uuid, 'Uuid');
initMetadataForClass(atomicfu$TraceBase, 'TraceBase');
initMetadataForObject(None, 'None');
initMetadataForClass(AtomicInt, 'AtomicInt');
initMetadataForInterface(Unique, 'Unique');
initMetadataForClass(UniqueImpl, 'UniqueImpl', UniqueImpl, VOID, [Unique]);
initMetadataForInterface(Visitable, 'Visitable');
initMetadataForClass(Edge, 'Edge', VOID, VOID, [Unique, Visitable], [1]);
initMetadataForClass(PortGraph, 'PortGraph', PortGraph, VOID, [Unique, Visitable]);
initMetadataForInterface(PortCapability, 'PortCapability');
initMetadataForClass(PortNode, 'PortNode', VOID, VOID, [Unique, Visitable, PortCapability]);
initMetadataForClass(Port, 'Port', VOID, VOID, [Visitable]);
initMetadataForClass(ConsumerPort, 'ConsumerPort', VOID, VOID, VOID, [1]);
initMetadataForClass(Key_0, 'Key');
initMetadataForCompanion(Companion_11);
initMetadataForClass(Type, 'Type');
initMetadataForCompanion(Companion_12);
initMetadataForClass(KeyType, 'KeyType');
initMetadataForClass(PortEvent, 'PortEvent');
initMetadataForClass(Created, 'Created');
initMetadataForClass(Connected, 'Connected');
initMetadataForClass(Disconnected, 'Disconnected');
protoOf(PortCapabilityImpl).registerProvider = registerProvider;
protoOf(PortCapabilityImpl).getProvider = getProvider;
protoOf(PortCapabilityImpl).registerConsumer = registerConsumer;
protoOf(PortCapabilityImpl).getConsumer = getConsumer;
initMetadataForClass(PortCapabilityImpl, 'PortCapabilityImpl', PortCapabilityImpl, VOID, [PortCapability]);
initMetadataForClass(ProviderPort, 'ProviderPort', VOID, VOID, VOID, [1]);
initMetadataForInterface(Selector, 'Selector');
initMetadataForObject(StructuralSelector_0, 'StructuralSelector', VOID, VOID, [Selector]);
initMetadataForObject(ConnectivitySelector_0, 'ConnectivitySelector', VOID, VOID, [Selector]);
initMetadataForInterface(Traverser, 'Traverser');
initMetadataForObject(DepthFirstTraverser_0, 'DepthFirstTraverser', VOID, VOID, [Traverser]);
initMetadataForObject(BreadthFirstTraverser_0, 'BreadthFirstTraverser', VOID, VOID, [Traverser]);
initMetadataForClass(PortGraphVisitor, 'PortGraphVisitor', PortGraphVisitor);
initMetadataForClass(HierarchyVisitor, 'HierarchyVisitor', HierarchyVisitor);
//endregion
//region block: init
Companion_instance = new Companion();
Companion_instance_0 = new Companion_0();
Companion_instance_1 = new Companion_1();
Companion_instance_2 = new Companion_2();
Unit_instance = new Unit();
Companion_instance_4 = new Companion_4();
CompletedContinuation_instance = new CompletedContinuation();
Companion_instance_5 = new Companion_5();
Companion_instance_6 = new Companion_6();
Companion_instance_7 = new Companion_7();
EmptyList_instance = new EmptyList();
EmptyIterator_instance = new EmptyIterator();
EmptyMap_instance = new EmptyMap();
EmptySet_instance = new EmptySet();
Key_instance = new Key();
EmptyCoroutineContext_instance = new EmptyCoroutineContext();
Companion_instance_9 = new Companion_9();
Companion_instance_11 = new Companion_11();
Companion_instance_12 = new Companion_12();
StructuralSelector_instance = new StructuralSelector_0();
ConnectivitySelector_instance = new ConnectivitySelector_0();
DepthFirstTraverser_instance = new DepthFirstTraverser_0();
BreadthFirstTraverser_instance = new BreadthFirstTraverser_0();
//endregion
//region block: exports
var KtList_0 = {};
KtList_0.fromJsArray = fromJsArray;
var KtMutableMap_0 = {};
KtMutableMap_0.fromJsMap = fromJsMap;
var KtMutableList_0 = {};
KtMutableList_0.fromJsArray = fromJsArray_0;
defineProp(Type, 'Companion', Companion_getInstance_11, VOID, true);
KeyType.invoke = invoke;
defineProp(KeyType, 'Companion', Companion_getInstance_12, VOID, true);
PortEvent.Created = Created;
PortEvent.Connected = Connected;
PortEvent.Disconnected = Disconnected;
var StructuralSelector = {getInstance: StructuralSelector_getInstance};
var ConnectivitySelector = {getInstance: ConnectivitySelector_getInstance};
var DepthFirstTraverser = {getInstance: DepthFirstTraverser_getInstance};
var BreadthFirstTraverser = {getInstance: BreadthFirstTraverser_getInstance};
export {
  KtList_0 as KtList,
  KtMutableMap_0 as KtMutableMap,
  KtMutableList_0 as KtMutableList,
  UniqueImpl as UniqueImpl,
  Edge as Edge,
  PortGraph as PortGraph,
  connectPort as connectPort,
  connectNode as connectNode,
  PortNode as PortNode,
  ConsumerPort as ConsumerPort,
  Port as Port,
  Key_0 as Key,
  Type as Type,
  KeyType as KeyType,
  PortEvent as PortEvent,
  PortCapabilityImpl as PortCapabilityImpl,
  ProviderPort as ProviderPort,
  StructuralSelector as StructuralSelector,
  ConnectivitySelector as ConnectivitySelector,
  DepthFirstTraverser as DepthFirstTraverser,
  BreadthFirstTraverser as BreadthFirstTraverser,
  PortGraphVisitor as PortGraphVisitor,
  HierarchyVisitor as HierarchyVisitor,
};
//endregion

//# sourceMappingURL=reaktor-reaktor-graph-port.mjs.map
