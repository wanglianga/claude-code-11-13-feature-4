import { createRouter, createWebHashHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/', redirect: '/subjects' },
  { path: '/subjects', name: 'subjects', component: () => import('../views/SubjectView.vue') },
  { path: '/tickets', name: 'tickets', component: () => import('../views/TicketListView.vue') },
  { path: '/tickets/:id', name: 'ticket-detail', component: () => import('../views/TicketDetailView.vue'), props: true },
  { path: '/lineage', name: 'lineage', component: () => import('../views/LineageView.vue') },
  { path: '/restore', name: 'restore', component: () => import('../views/RestoreView.vue') },
  { path: '/restore/:id', name: 'restore-detail', component: () => import('../views/RestoreDetailView.vue'), props: true }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  const authed = !!localStorage.getItem('rtbf-auth')
  if (!authed && to.name !== 'login') {
    return { name: 'login' }
  }
})

export default router
