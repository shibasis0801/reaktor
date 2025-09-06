import { AppBar, Box, Container, Toolbar, Typography } from '@mui/material'
import { Link, Outlet } from 'react-router-dom'

export default function App() {
  return (
    <>
      <AppBar position="static">
        <Toolbar>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            Reaktor TS React
          </Typography>
          <Box sx={{ display: 'flex', gap: 2 }}>
            <Link to="/">Home</Link>
            <Link to="/about">About</Link>
          </Box>
        </Toolbar>
      </AppBar>
      <Container sx={{ mt: 4 }}>
        <Outlet />
      </Container>
    </>
  )
}
