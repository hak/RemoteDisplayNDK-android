/*
 * Copyright 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "Cube.h"

#include "Cube.inl"

//--------------------------------------------------------------------------------
// Cube.cpp
// Renders a colorful cube
//--------------------------------------------------------------------------------

//--------------------------------------------------------------------------------
// Ctor
//--------------------------------------------------------------------------------
Cube::Cube()
{

}

//--------------------------------------------------------------------------------
// Dtor
//--------------------------------------------------------------------------------
Cube::~Cube()
{

}

void Cube::Init() {
    if (!LoadShaders(&shader_param_)) {
       LOGE("Loading shaders failed!");
        return;
    };


}

void Cube::Render(GLfloat* mvpMatrix, bool changeColor) {
    // Add program to OpenGL environment
    glUseProgram( shader_param_.program_ );
    checkGlError("glUseProgram");

    glVertexAttribPointer( shader_param_.position_, COORDS_PER_VERTEX, GL_FLOAT, GL_FALSE,
                           VERTEX_STRIDE, VERTICES );
    checkGlError("glVertexAttribPointer");

    // Enable a handle to the triangle vertices
    glEnableVertexAttribArray( shader_param_.position_ );
    checkGlError("glEnableVertexAttribArray");

    // Prepare the color data
    if (changeColor) {
        glVertexAttribPointer(
                shader_param_.color_, COORDS_PER_COLORS,
                GL_FLOAT, false,
                COLORS_STRIDE, COLORS1);
        checkGlError("glVertexAttribPointer");
    } else {
        glVertexAttribPointer(
                shader_param_.color_, COORDS_PER_COLORS,
                GL_FLOAT, false,
                COLORS_STRIDE, COLORS2);
        checkGlError("glVertexAttribPointer");
    }

    // Enable a handle to the color vertices
    glEnableVertexAttribArray( shader_param_.color_ );
    checkGlError("glEnableVertexAttribArray");

    // Get handle to shape's transformation matrix
    glUniformMatrix4fv( shader_param_.mvp_, 1, false, mvpMatrix );
    checkGlError("glUniformMatrix4fv");

    // Draw the shape
    glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_SHORT, INDICES);
    checkGlError("glDrawElements");

    // Disable vertex array
    glDisableVertexAttribArray( shader_param_.position_ );
    checkGlError("glDisableVertexAttribArray");

    // Disable color array
    glDisableVertexAttribArray( shader_param_.color_ );
    checkGlError("glDisableVertexAttribArray");
}

bool Cube::LoadShaders(SHADER_PARAMS* params) {
    GLuint program;
    GLuint vert_shader, frag_shader;

    // Create shader program
    program = glCreateProgram();
    LOGI( "Created Shader %d", program );

    // Create and compile vertex shader
    if( !ndk_helper::shader::CompileShader( &vert_shader, GL_VERTEX_SHADER,
                                            VERTEX_SHADER_CODE, strlen(VERTEX_SHADER_CODE) ) )
    {
        LOGI( "Failed to compile vertex shader" );
        glDeleteProgram( program );
        return false;
    }

    // Create and compile fragment shader
    if( !ndk_helper::shader::CompileShader( &frag_shader, GL_FRAGMENT_SHADER,
                                            FRAGMENT_SHADER_CODE, strlen(FRAGMENT_SHADER_CODE) ) )
    {
        LOGE( "Failed to compile fragment shader" );
        glDeleteProgram( program );
        return false;
    }

    // Attach vertex shader to program
    glAttachShader( program, vert_shader );

    // Attach fragment shader to program
    glAttachShader( program, frag_shader );

    // Link program
    if( !ndk_helper::shader::LinkProgram( program ) )
    {
        if( vert_shader )
        {
            glDeleteShader( vert_shader );
            vert_shader = 0;
        }
        if( frag_shader )
        {
            glDeleteShader( frag_shader );
            frag_shader = 0;
        }
        if( program )
        {
            glDeleteProgram( program );
        }

        LOGE( "Linking shader %d failed", program );

        return false;
    }

    // Release vertex and fragment shaders
    if( vert_shader )
        glDeleteShader( vert_shader );
    if( frag_shader )
        glDeleteShader( frag_shader );

    params->program_ = program;
    params->position_ = glGetAttribLocation(program, "vPosition");
    checkGlError("glGetUniformLocation vPosition");
    params->color_ = glGetAttribLocation(program, "vColor");
    checkGlError("glGetUniformLocation vColor");
    params->mvp_ = glGetUniformLocation(program, "uMVPMatrix");
    checkGlError("glGetUniformLocation uMVPMatrix");

    LOGI( "Shader %d loaded successfully", program );

    return true;
}