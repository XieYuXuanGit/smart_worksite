<script setup lang="ts" generic="T extends Record<string, any>">
withDefaults(defineProps<{
  columns: { prop: string; label: string; width?: number | string; slot?: string }[];
  data: T[];
  loading?: boolean;
  error?: string;
  total?: number;
  pageNo?: number;
  pageSize?: number;
  maxHeight?: number | string;
  selectable?: boolean;
  selectableFilter?: (row: T) => boolean;
}>(), { total: 0, pageNo: 1, pageSize: 10, selectable: false });
const emit = defineEmits<{ pageChange: [pageNo: number, pageSize: number]; selectionChange: [rows: T[]] }>();
function onPageChange(page: number, size: number) { emit('pageChange', page, size); }
function onSelectionChange(rows: T[]) { emit('selectionChange', rows); }
</script>

<template>
  <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" style="margin-bottom: 12px" />
  <el-table v-loading="loading" :data="data" :max-height="maxHeight" border stripe style="width: 100%" @selection-change="onSelectionChange">
    <template #empty><slot name="empty"><el-empty description="暂无数据" /></slot></template>
    <el-table-column v-if="selectable" type="selection" width="46" :selectable="selectableFilter" />
    <el-table-column v-for="col in columns" :key="col.prop" :prop="col.prop" :label="col.label" :width="col.width">
      <template v-if="col.slot" #default="scope"><slot :name="col.slot" v-bind="scope" /></template>
    </el-table-column>
    <slot />
  </el-table>
  <div v-if="total > 0" class="pager">
    <el-pagination background layout="total, sizes, prev, pager, next" :total="total" :current-page="pageNo" :page-size="pageSize" @current-change="(page: number) => onPageChange(page, pageSize)" @size-change="(size: number) => onPageChange(1, size)" />
  </div>
</template>

<style scoped>.pager { display: flex; justify-content: flex-end; margin-top: 14px; }</style>
