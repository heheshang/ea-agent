import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    {
      path: '/',
      component: () => import('../views/LayoutView.vue'),
      children: [
        { path: '', redirect: '/workbench' },
        { path: 'workbench', name: 'workbench', component: () => import('../views/AgentWorkbenchView.vue') },
        { path: 'customers', name: 'customers', component: () => import('../views/CustomersView.vue') },
        { path: 'campaigns', name: 'campaigns', component: () => import('../views/CampaignsView.vue') },
        { path: 'stats', name: 'stats', component: () => import('../views/StatsView.vue') },
        { path: 'retention', name: 'retention', component: () => import('../views/RetentionView.vue') },
        { path: 'knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
        { path: 'ontology', name: 'ontology', component: () => import('../views/OntologyView.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const token = localStorage.getItem('ea:token')
  if (!token && to.name !== 'login') {
    return { name: 'login' }
  }
  if (token && to.name === 'login') {
    return { name: 'workbench' }
  }
  return true
})

export default router